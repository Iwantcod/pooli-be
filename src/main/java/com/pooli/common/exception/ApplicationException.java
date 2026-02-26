package com.pooli.common.exception;

public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * (주로 사용)사용 목적:
     * - ErrorCode에 정의된 기본 메시지를 그대로 사용하는 표준 비즈니스 예외 생성자
     *
     * 동작:
     * - RuntimeException의 message를 errorCode.getMessage()로 설정
     * - errorCode를 내부 필드에 보관
     */
    public ApplicationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 사용 목적:
     * - 동일한 ErrorCode를 사용하되, 상황에 따라 메시지를 동적으로 변경해야 할 때 사용
     *
     * 동작:
     * - RuntimeException의 message를 overrideMessage로 설정
     * - errorCode는 그대로 유지
     */
    public ApplicationException(ErrorCode errorCode, String overrideMessage) {
        super(overrideMessage);
        this.errorCode = errorCode;
    }

    /**
     * 사용 목적:
     * - 다른 예외를 감싸서 도메인 예외로 변환하고, 원인 예외를 함께 유지할 때 사용
     *
     * 동작:
     * - RuntimeException의 message를 errorCode의 기본 메시지로 설정
     * - cause를 함께 전달하여 예외 체인을 유지
     * - errorCode를 내부 필드에 보관
     */
    public ApplicationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}