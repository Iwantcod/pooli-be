package com.pooli.traffic.service.decision;

/**
 * 트래픽 차감 파이프라인에서 Redis 인프라 장애를 구분하기 위한 단계 키입니다.
 *
 * <p>로그/예외 메시지 키를 단계별로 고정해 운영 추적 시 원인 구간을 빠르게 식별합니다.
 */
public enum TrafficFailureStage {
    /** 차단성 정책 검증 단계(`ensureLoaded` + policy check)에서의 장애 구간입니다. */
    POLICY_CHECK("policy_check"),
    /** 초기 차감 Lua 실행(개인/공유) 단계의 장애 구간입니다. */
    DEDUCT("deduct"),
    /** HYDRATE 보정 후 재차감 단계의 장애 구간입니다. */
    HYDRATE("hydrate");

    /** 로그 키 조합에 사용하는 단계 식별자 문자열입니다. */
    private final String stageKey;

    /**
     * 단계 식별자 문자열을 enum 상수에 바인딩합니다.
     *
     * @param stageKey 로그/예외 메시지 키 조합에 사용하는 단계 키
     */
    TrafficFailureStage(String stageKey) {
        this.stageKey = stageKey;
    }

    /**
     * 현재 단계의 원시 식별자 문자열을 반환합니다.
     *
     * @return 예: {@code policy_check}, {@code deduct}
     */
    public String stageKey() {
        return stageKey;
    }

    /**
     * retryable Redis 인프라 장애 로그 키를 생성합니다.
     *
     * @return 예: {@code traffic_deduct_redis_retryable_failure}
     */
    public String retryableFailureLogKey() {
        return "traffic_" + stageKey + "_redis_retryable_failure";
    }

    /**
     * non-retryable Redis 인프라 장애 로그 키를 생성합니다.
     *
     * @return 예: {@code traffic_policy_check_redis_non_retryable_failure}
     */
    public String nonRetryableFailureLogKey() {
        return "traffic_" + stageKey + "_redis_non_retryable_failure";
    }

    /**
     * Redis 재시도 소진 로그 키를 생성합니다.
     *
     * @return 예: {@code traffic_hydrate_redis_retry_exhausted}
     */
    public String retryExhaustedLogKey() {
        return "traffic_" + stageKey + "_redis_retry_exhausted";
    }
}
