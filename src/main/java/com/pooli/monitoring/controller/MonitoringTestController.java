package com.pooli.monitoring.controller;

import com.pooli.common.exception.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pooli.common.exception.CommonErrorCode;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
@Slf4j
public class MonitoringTestController {

    private static final String TRACE_ID_KEY = "traceId";


    @GetMapping("/error")
    public ResponseEntity<Void> testErrorLog(HttpServletRequest request) {

        String traceId = MDC.get(TRACE_ID_KEY);

        log.error(
                "application_error traceId={} uri={} status={} type=test_error message={}",
                traceId,
                request.getRequestURI(),
                500,
                "This is Loki error log test"
        );

        return ResponseEntity.ok().build();
    }

    @GetMapping("/app-error")
    public void testAppError() {
        throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}