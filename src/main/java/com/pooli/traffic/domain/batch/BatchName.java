package com.pooli.traffic.domain.batch;

/**
 * 일별 배치를 target row 생성 단계와 Redis 사용량 동기화 단계로 분리하는 고정 이름이다.
 */
public enum BatchName {
    /** LINE 기준 target row set을 준비하는 batch */
    LINE_DAILY_TARGET_INSERT_BATCH,

    /** Redis 일별 사용량을 MySQL 사용량 테이블로 동기화하는 batch */
    LINE_DAILY_USAGE_SYNC_BATCH,

    /** 복구 phase 0 hydrate target row set을 준비하는 batch */
    RESTORE_P0_TARGET_INSERT,

    /** 복구 phase 0에서 line, family, 전역 policy Redis key를 hydrate하는 batch */
    RESTORE_P0_REDIS_HYDRATE,

    /** 복구 phase 1 daily app replay target row set을 준비하는 batch */
    RESTORE_P1_TARGET_INSERT,

    /** 복구 phase 1에서 DAILY_APP_TOTAL_DATA 기준 Redis 사용량과 잔량을 replay하는 batch */
    RESTORE_P1_DAILY_APP_REPLAY,

    /** 복구 phase 2에서 TRAFFIC_DEDUCT_DONE 기준 replay, 검증, 보정을 수행하는 batch */
    RESTORE_P2_DONE_LOG_REPLAY
}
