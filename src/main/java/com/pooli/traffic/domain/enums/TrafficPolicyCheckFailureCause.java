package com.pooli.traffic.domain.enums;

/**
 * Policy Check 단계 실패 원인을 표준 코드로 표현합니다.
 */
public enum TrafficPolicyCheckFailureCause {
    /** 실패 없음(정상 처리 또는 차단/허용 판정 완료) */
    NONE,
    /** ensureLoaded 단계에서 발생한 retryable 인프라 실패 */
    ENSURE_LOADED_RETRYABLE,
    /** policy check Lua 실행 단계에서 발생한 retryable 인프라 실패 */
    POLICY_CHECK_RETRYABLE,
    /** policy check 단계의 non-retryable 실패(재시도/DB fallback 대상 아님) */
    POLICY_CHECK_NON_RETRYABLE
}
