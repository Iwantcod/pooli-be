package com.pooli.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    /* =========================
     * 400 - 입력/Validation 계열
     * ========================= */
    INVALID_REQUEST_FORMAT(
            HttpStatus.BAD_REQUEST,
            "COMMON:4000",
            "요청 형식이 일치하지 않습니다."
    ),

    INVALID_REQUEST_BODY(
            HttpStatus.BAD_REQUEST,
            "COMMON:4001",
            "요청 DTO 필드 유효성 검증에 실패했습니다."
    ),

    INVALID_REQUEST_PARAM(
            HttpStatus.BAD_REQUEST,
            "COMMON:4002",
            "요청 파라미터 유효성 검증에 실패했습니다."
    ),

    INVALID_REQUEST_PARAM_TYPE(
            HttpStatus.BAD_REQUEST,
            "COMMON:4003",
            "요청 파라미터 타입이 일치하지 않습니다."
    ),

    MISSING_REQUEST_PARAM(
            HttpStatus.BAD_REQUEST,
            "COMMON:4004",
            "필수 요청 파라미터가 누락되었습니다."
    ),

    METHOD_NOT_SUPPORTED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "COMMON:4005",
            "지원하지 않는 HTTP 메서드입니다."
    ),

    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "COMMON:4006",
            "지원하지 않는 Content-Type입니다."
    ),


    /* =========================
     * 500 - 서버/인프라 계열
     * ========================= */
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5000",
            "서버 내부 오류가 발생했습니다."
    ),

    DATABASE_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5001",
            "데이터베이스 처리 중 오류가 발생했습니다."
    ),

    EXTERNAL_SYSTEM_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5002",
            "외부 시스템 호출 중 오류가 발생했습니다."
    ),

    TRANSACTION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5003",
            "요청 처리 중 오류가 발생했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public HttpStatus getHttpStatus() {
        return null;
    }

    @Override
    public String getCode() {
        return "";
    }

    @Override
    public String getMessage() {
        return "";
    }
}
