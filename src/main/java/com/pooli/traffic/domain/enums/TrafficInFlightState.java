package com.pooli.traffic.domain.enums;

/**
 * traceId 단위 in-flight 멱등키에 기록하는 상태값입니다.
 */
public enum TrafficInFlightState {
    CLAIMED,
    REDIS_RETRY_1,
    REDIS_RETRY_2,
    REDIS_RETRY_3,
    DB_FALLBACK,
    DONE;

    /**
     * 재시도 횟수를 상태값으로 변환합니다.
     */
    public static TrafficInFlightState fromRetryAttempt(int attempt) {
        return switch (attempt) {
            case 1 -> REDIS_RETRY_1;
            case 2 -> REDIS_RETRY_2;
            default -> REDIS_RETRY_3;
        };
    }
}
