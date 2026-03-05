package com.pooli.permission.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PermissionErrorCode implements ErrorCode {

    // 400 Bad Request
//    PERMISSION_NAME_BLANK(HttpStatus.BAD_REQUEST, "PERMISSION-4000", "권한 이름은 비어 있을 수 없습니다."),
//    PERMISSION_NAME_TOO_LONG(HttpStatus.BAD_REQUEST, "PERMISSION-4001", "권한 이름은 20자 이하여야 합니다."),
//    PERMISSION_IS_ENABLE_MISSING(HttpStatus.BAD_REQUEST, "PERMISSION-4002", "권한 활성화 값(isEnable)이 누락되었습니다."),
//    ROLE_TRANSFER_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "PERMISSION-4003", "역할 양도 요청 값이 올바르지 않습니다."),
    ROLE_TRANSFER_SELF(HttpStatus.BAD_REQUEST, "PERMISSION-4004", "자기 자신에게 역할을 양도할 수 없습니다."),

    // 404 Not Found
    PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4400", "해당 권한 정보가 존재하지 않습니다."),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4401", "대상 회선 정보가 존재하지 않습니다."),
    ROLE_TRANSFER_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4402", "역할 양도 대상 사용자가 존재하지 않습니다."),
    ROLE_TRANSFER_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4403", "역할 양도 출발 사용자 정보를 찾을 수 없습니다."),
    FAMILY_LINE_MAPPING_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-4404", "해당 가족-회선 매핑 정보가 존재하지 않습니다."),

    // 409 Conflict
    DUPLICATE_PERMISSION_TITLE(HttpStatus.CONFLICT, "PERMISSION-4900", "이미 존재하는 권한 이름입니다."),
    ROLE_TRANSFER_DIFFERENT_FAMILY(HttpStatus.CONFLICT, "PERMISSION-4901", "같은 가족 내 사용자에게만 역할 양도가 가능합니다."),
    ROLE_TRANSFER_TARGET_ALREADY_REPRESENTATIVE(HttpStatus.CONFLICT, "PERMISSION-4902", "대상 사용자는 이미 가족 대표자입니다."),

    // 500 Internal Server Error
    MEMBER_PERMISSION_APPLY_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "PERMISSION-5000", "구성원 권한 반영 중 오류가 발생했습니다.");

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
