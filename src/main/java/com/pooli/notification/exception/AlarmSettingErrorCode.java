package com.pooli.notification.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AlarmSettingErrorCode implements ErrorCode {

    // 400 Bad Request
    ALARM_TYPE_MISSING(HttpStatus.BAD_REQUEST, "ALARM-4001", "알람 타입이 누락되었습니다."),

    // 404 Not Found
    ALARM_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "ALARM-4401", "알람 설정 정보를 찾을 수 없습니다."),

    // 409 Conflict
    ALARM_SETTING_DUPLICATE(HttpStatus.CONFLICT, "ALARM-4901", "이미 알람 설정이 존재합니다."),

    // 500
    ALARM_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "ALARM-5001", "알람 설정 변경 중 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AlarmSettingErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}