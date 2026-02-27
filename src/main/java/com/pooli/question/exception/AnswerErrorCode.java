package com.pooli.question.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AnswerErrorCode implements ErrorCode {

    ANSWER_ALREADY_EXISTS(
            HttpStatus.BAD_REQUEST,
            "ANSWER:4001",
            "이미 답변이 존재합니다."
    ),

    // 답변 존재하지 않음
    ANSWER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ANSWER:4041",
            "해당 답변이 존재하지 않습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AnswerErrorCode(HttpStatus httpStatus, String code, String message) {
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