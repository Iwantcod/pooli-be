package com.pooli.common.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    HttpStatus getHttpStatus();
    String getCode();      // 안정 식별자: 예) PLAN-0001
    String getMessage();   // 기본 메시지
}