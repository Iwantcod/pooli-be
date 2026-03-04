package com.pooli.family.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SharedPoolErrorCode implements ErrorCode {

    // 공유풀 데이터 조회 실패
    SHARED_POOL_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SHARED_POOL:4400",
            "요청한 공유풀 데이터를 찾을 수 없습니다."
    ),

    // 회선 정보 조회 실패
    LINE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SHARED_POOL:4401",
            "회선 정보를 찾을 수 없습니다."
    ),

    // 가족 구성원이 아님
    NOT_FAMILY_MEMBER(
            HttpStatus.BAD_REQUEST,
            "SHARED_POOL:4000",
            "해당 회선은 이 가족의 구성원이 아닙니다."
    ),

    // 잔여 데이터 부족
    INSUFFICIENT_DATA(
            HttpStatus.BAD_REQUEST,
            "SHARED_POOL:4001",
            "잔여 데이터가 부족합니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    SharedPoolErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}
