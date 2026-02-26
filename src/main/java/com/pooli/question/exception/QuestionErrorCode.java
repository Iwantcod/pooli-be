package com.pooli.question.exception;

import com.pooli.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum QuestionErrorCode implements ErrorCode {

    // 활성화된 카테고리 없음
    QUESTION_CATEGORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "QUESTION:4041",
            "카테고리가 존재하지 않습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    QuestionErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}