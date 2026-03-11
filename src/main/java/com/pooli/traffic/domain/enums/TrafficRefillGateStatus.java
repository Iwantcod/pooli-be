package com.pooli.traffic.domain.enums;

/**
 * refill_gate.lua 스크립트의 반환 상태를 표현합니다.
 * 리필 진입 여부와 락 획득 상태를 오케스트레이터 분기에서 사용합니다.
 */
public enum TrafficRefillGateStatus {
    FAIL,
    SKIP,
    OK,
    WAIT
}
