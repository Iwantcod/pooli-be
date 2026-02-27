package com.pooli.family.error;

import org.springframework.http.HttpStatus;

import com.pooli.common.exception.ErrorCode;

public enum FamilyErrorCode implements ErrorCode {
	
	FAM_NOT_FOUND(HttpStatus.NOT_FOUND, "FAMILY-4401", "해당 가족 관련 정보를 찾을 수 없습니다.");

	
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    FamilyErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }

}
