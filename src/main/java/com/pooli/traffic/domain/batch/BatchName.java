package com.pooli.traffic.domain.batch;

/**
 * 일별 배치를 target row 생성 단계와 Redis 사용량 동기화 단계로 분리하는 고정 이름이다.
 */
public enum BatchName {
    /** LINE 기준 target row set을 준비하는 batch */
    LINE_DAILY_TARGET_INSERT_BATCH,

    /** Redis 일별 사용량을 MySQL 사용량 테이블로 동기화하는 batch */
    LINE_DAILY_USAGE_SYNC_BATCH
}
