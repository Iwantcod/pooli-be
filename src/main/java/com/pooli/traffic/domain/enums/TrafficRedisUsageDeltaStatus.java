package com.pooli.traffic.domain.enums;

/**
 * Redis usage delta replay 레코드의 처리 상태입니다.
 */
public enum TrafficRedisUsageDeltaStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAIL
}
