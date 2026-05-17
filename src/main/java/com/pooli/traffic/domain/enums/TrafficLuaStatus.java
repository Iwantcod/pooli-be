package com.pooli.traffic.domain.enums;

/**
 * Lua 차감 스크립트가 반환하는 상태 코드를 표현합니다.
 * 오케스트레이터는 이 값을 기준으로 정책 차단, hydrate 복구, 최종 종료 상태를 결정합니다.
 */
public enum TrafficLuaStatus {
    /** 요청량을 정상 처리했거나 정책 검증이 통과된 상태입니다. */
    OK,

    /** 개인풀, 공유풀, QoS 모두로 남은 요청량을 더 이상 처리할 수 없는 상태입니다. */
    NO_BALANCE,

    /** 즉시 차단 정책에 걸려 차감을 진행하지 않아야 하는 상태입니다. */
    BLOCKED_IMMEDIATE,

    /** 반복 차단 정책에 걸려 차감을 진행하지 않아야 하는 상태입니다. */
    BLOCKED_REPEAT,

    /** 회선 일일 총 사용량 제한으로 처리 대상 요청량이 제한된 상태입니다. */
    HIT_DAILY_LIMIT,

    /** 월간 공유풀 사용량 제한으로 공유풀 차감 가능량이 제한된 상태입니다. */
    HIT_MONTHLY_SHARED_LIMIT,

    /** 앱별 일일 사용량 제한으로 처리 대상 요청량이 제한된 상태입니다. */
    HIT_APP_DAILY_LIMIT,

    /** 앱 속도 제한 정책으로 현재 tick의 처리량이 제한된 상태입니다. */
    HIT_APP_SPEED,

    /** 잔량 차감 없이 QoS 허용량으로 요청량을 처리한 상태입니다. */
    QOS,

    /** 전역 정책 스냅샷이 Redis에 없어 정책 hydrate 후 재시도가 필요한 상태입니다. */
    GLOBAL_POLICY_HYDRATE,

    /** 월별 개인/공유 잔량 또는 QoS hash가 Redis에 없어 잔량 hydrate 후 재시도가 필요한 상태입니다. */
    HYDRATE,

    /** 월별 개인 잔량 snapshot(`amount`, `qos`)이 Redis에 없어 개인 hydrate 후 재시도가 필요한 상태입니다. */
    HYDRATE_INDIVIDUAL,

    /** 월별 공유 잔량 snapshot(`amount`)이 Redis에 없어 공유 hydrate 후 재시도가 필요한 상태입니다. */
    HYDRATE_SHARED,

    /** Lua 입력 검증 실패 또는 스크립트 처리 중 오류로 정상 상태를 확정할 수 없는 상태입니다. */
    ERROR
}
