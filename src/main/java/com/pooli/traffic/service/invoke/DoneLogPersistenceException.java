package com.pooli.traffic.service.invoke;

/**
 * done log 저장 실패를 식별하기 위한 래핑 예외입니다.<br>
 * TrafficStreamConsumerRunner 내부 흐름 제어용 예외입니다.
 */
final class DoneLogPersistenceException extends RuntimeException {

    /**
     * 예외 메시지와 원인 예외를 생성합니다.
     *
     * @param message 래핑 메시지
     * @param cause 원인 예외
     */
    DoneLogPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
