package com.pooli.permission.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PermissionErrorCode implements ErrorCode {

    // Permission (404)
    PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4001", "권한을 찾을 수 없습니다."),

    // Permission (409)
    DUPLICATE_PERMISSION_TITLE(HttpStatus.CONFLICT, "PERMISSION-4091", "이미 존재하는 권한 이름입니다."),

    // MemberPermission (404)
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4002", "구성원 정보를 찾을 수 없습니다."),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4003", "회선 정보를 찾을 수 없습니다."),
    MEMBER_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4004", "구성원 권한 정보를 찾을 수 없습니다."),

    // Role (404)
    ROLE_TRANSFER_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4005", "역할 양도 대상 사용자를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    PermissionErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() { return httpStatus; }

    @Override
    public String getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
