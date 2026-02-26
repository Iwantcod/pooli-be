package com.pooli.common.exception;

import com.pooli.common.dto.ErrorResDto;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResDto> handleApplicationException(ApplicationException ex) {
        ErrorCode errorCode = ex.getErrorCode();

        ErrorResDto body = ErrorResDto.builder()
                .code(errorCode.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get("traceId"))
                .build();

        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResDto> handleUnexpected(Exception ex) {
        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON-0000")
                .message("서버 내부 오류가 발생했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get("traceId"))
                .build();

        return ResponseEntity.internalServerError().body(body);
    }
}