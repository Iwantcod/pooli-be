package com.pooli.data.error;

import org.springframework.http.HttpStatus;

import com.pooli.common.exception.ErrorCode;

public enum DataErrorCode implements ErrorCode {
	
	
	DATA_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DATA:4401",
            "해당 데이터가 존재하지 않습니다."
    ),
	INVALID_MONTH(
            HttpStatus.BAD_REQUEST,
            "DATA:4001",
            "yearMonth는 YYYYMM 형식이어야 합니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    DataErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }

}
