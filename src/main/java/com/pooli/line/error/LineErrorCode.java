package com.pooli.line.error;

import org.springframework.http.HttpStatus;

import com.pooli.common.exception.ErrorCode;

public enum LineErrorCode implements ErrorCode {
	
	// S3 Presigned URL 생성 실패
    LINE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "LINE:4401",
            "관련 회선 정보가 존재하지 않습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    LineErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }

}
