package com.pooli.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String CACHED_BODY_ATTR = "cachedRequestBody";
    private static final int MAX_BODY_LENGTH = 1000; // 로그에 남길 최대 body 길이

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (request.getRequestURI().startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_KEY, traceId);

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            String rawBody = new String(wrappedRequest.getContentAsByteArray(), StandardCharsets.UTF_8);
            String maskedBody = maskSensitiveInfo(rawBody);
            String truncatedBody = truncate(maskedBody, MAX_BODY_LENGTH);
            request.setAttribute(CACHED_BODY_ATTR, truncatedBody);

            MDC.clear();
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...(truncated)";
    }

    private String maskSensitiveInfo(String body) {
        if (body == null) return null;
        return body
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]+\"", "\"password\":\"****\"");
    }
}