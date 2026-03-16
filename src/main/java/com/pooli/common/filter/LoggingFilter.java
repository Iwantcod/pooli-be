package com.pooli.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청/응답 로깅 필터 (단일 필터로 통합)
 *
 * - traceId 생성 및 MDC 등록
 * - /api/auth/** body 로깅 금지
 * - 민감 필드(password, token, authorization, cookie, secret, credential) 마스킹
 * - 정상 응답(2xx/3xx): body 미출력
 * - 에러 응답(4xx/5xx): 마스킹된 body 출력
 * - GlobalExceptionHandler용으로 마스킹된 body를 request attribute에 캐시
 */
@Component
@Order(1)
public class LoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    private static final String TRACE_ID_KEY = "traceId";
    static final String CACHED_BODY_ATTR = "cachedRequestBody";
    private static final int MAX_BODY_LENGTH = 1000;

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)\"(password|token|access_?token|refresh_?token|authorization|cookie|secret|credential)\"\\s*:\\s*\"[^\"]*\"");
    private static final String SENSITIVE_REPLACEMENT = "\"$1\":\"****\"";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();

        if (uri.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request);

        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_KEY, traceId);

        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            long latency = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String method = request.getMethod();

            String maskedBody = processBody(wrappedRequest, uri);

            // GlobalExceptionHandler에서 재사용할 수 있도록 캐시
            wrappedRequest.setAttribute(CACHED_BODY_ATTR, maskedBody);

            // 정규화된 endpoint 패턴 사용 (예: /api/users/{id})
            // path variable이 없으면 raw URI와 동일
            String endpoint = resolveEndpoint(wrappedRequest, uri);

            // MDC에 구조화 필드 추가 → LogstashEncoder가 JSON 최상위 필드로 출력
            MDC.put("apiUri", endpoint);
            MDC.put("method", method);
            MDC.put("status", String.valueOf(status));
            MDC.put("latency", String.valueOf(latency));

            if (status >= 400) {
                log.info("request_log body={}", maskedBody);
            } else {
                log.info("request_log");
            }

            MDC.clear();
        }
    }

    private String processBody(ContentCachingRequestWrapper request, String uri) {
        if (uri.startsWith("/api/auth/")) {
            return "[REDACTED]";
        }

        String rawBody = new String(
                request.getContentAsByteArray(), StandardCharsets.UTF_8);

        if (rawBody.isEmpty()) {
            return "";
        }

        String masked = SENSITIVE_PATTERN.matcher(rawBody)
                .replaceAll(SENSITIVE_REPLACEMENT);
        return truncate(masked, MAX_BODY_LENGTH);
    }

    /**
     * Spring MVC가 매핑한 패턴(예: /api/users/{id})을 반환한다.
     * 매핑 정보가 없으면(404, static resource 등) raw URI를 그대로 사용한다.
     */
    private String resolveEndpoint(HttpServletRequest request, String rawUri) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : rawUri;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength
                ? str
                : str.substring(0, maxLength) + "...(truncated)";
    }
}
