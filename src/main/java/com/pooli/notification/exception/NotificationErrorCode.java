package com.pooli.notification.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ErrorCode {

    LINE_ID_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "NOTI:4001",
            "DIRECT 타입일 경우 lineId는 필수입니다."
    ),

    INVALID_TARGET_CONDITION(
            HttpStatus.BAD_REQUEST,
            "NOTI:4002",
            "targetType과 요청 값이 일치하지 않습니다."
    ),

    INVALID_ALARM_CODE(
            HttpStatus.BAD_REQUEST,
            "NOTI:4003",
            "알림 설정 대상이 아닌 코드입니다."
    ),

    NOTIFICATION_TARGET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOTI:4401",
            "알림을 보낼 대상이 존재하지 않습니다."
    ),
    ALARM_HISTORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOTI:4402",
            "알림 내역이 존재하지 않습니다."
    ),
    NOTIFICATION_SAVE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "NOTI:5001",
            "알림 저장 중 오류가 발생했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    NotificationErrorCode(HttpStatus httpStatus, String code, String message) {
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