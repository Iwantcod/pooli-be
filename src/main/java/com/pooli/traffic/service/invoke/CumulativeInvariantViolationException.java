package com.pooli.traffic.service.invoke;

/**
 * 누적 차감량 불변식(apiTotalData 초과 금지) 위반을 표현하는 런타임 예외입니다.<br>
 * TrafficStreamConsumerRunner 내부 흐름 제어용 예외입니다.
 */
final class CumulativeInvariantViolationException extends RuntimeException {

    /**
     * 예외 메시지를 생성합니다.
     *
     * @param message 위반 상세 메시지
     */
    CumulativeInvariantViolationException(String message) {
        super(message);
    }
}
