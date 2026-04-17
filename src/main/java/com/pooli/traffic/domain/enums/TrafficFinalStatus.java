package com.pooli.traffic.domain.enums;

/**
 * 트래픽 이벤트 단일 처리 종료 후 최종 결과 상태를 표현합니다.
 */
public enum TrafficFinalStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    RECLAIM_RETRY_EXCEEDED,
    FAILED
}
