package com.pooli.common.exception;

import org.springframework.http.HttpStatus;

public enum UploadErrorCode implements ErrorCode {

    // 요청 구조 이상 (files null/empty 등)
    INVALID_UPLOAD_REQUEST(
            HttpStatus.BAD_REQUEST,
            "UPLOAD:4001",
            "업로드 요청 값이 올바르지 않습니다."
    ),

    // 파일 3개 초과
    FILE_COUNT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "UPLOAD:4002",
            "파일은 최대 3개까지 업로드할 수 있습니다."
    ),

    // 개별 파일 정보 부족 / contentType 허용 안됨
    INVALID_FILE(
            HttpStatus.BAD_REQUEST,
            "UPLOAD:4003",
            "파일 정보가 부족하거나 허용되지 않는 형식입니다."
    ),

    // domain 허용값 아님
    INVALID_DOMAIN(
            HttpStatus.BAD_REQUEST,
            "UPLOAD:4004",
            "허용되지 않은 업로드 도메인입니다."
    ),

    // S3 Presigned URL 생성 실패
    PRESIGNED_URL_GENERATION_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "UPLOAD:5001",
            "Presigned URL 생성 중 오류가 발생했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    UploadErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}