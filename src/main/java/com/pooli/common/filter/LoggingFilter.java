package com.pooli.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class LoggingFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        long start = System.currentTimeMillis();

        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);

        // request wrapper
        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request);

        try {

            filterChain.doFilter(wrappedRequest, response);

        } finally {

            long latency = System.currentTimeMillis() - start;

            String body = new String(
                    wrappedRequest.getContentAsByteArray(),
                    StandardCharsets.UTF_8
            );

            log.info(
                    "apiUri={} method={} status={} latency={}ms body={}",
                    request.getRequestURI(),
                    request.getMethod(),
                    response.getStatus(),
                    latency,
                    body
            );

            MDC.clear();
        }
    }
}