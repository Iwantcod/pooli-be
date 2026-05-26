package com.pooli.traffic.domain.batch;

/**
 * LINE_DAILY_BATCH_TARGET의 line 단위 처리 상태이다.
 * worker는 PENDING, RETRYABLE, lease timeout이 지난 PROCESSING row만 선점 대상으로 삼는다.
 */
public enum LineDailyBatchTargetStatus {
    /** 아직 worker가 선점하지 않은 target row이다. */
    PENDING,

    /** worker가 선점하여 Redis 조회와 DB 반영을 수행 중인 row이다. */
    PROCESSING,

    /** Redis 사용량을 DB에 정상 반영한 terminal 상태이다. */
    DONE,

    /** 재시도 가능한 실패로 다시 선점될 수 있는 상태이다. */
    RETRYABLE,

    /** 재시도 한도를 초과했거나 자동 복구가 불가능한 terminal 실패 상태이다. */
    FAILED,

    /** 동기화 대상 Redis key가 모두 없어 DB 반영 없이 종료된 terminal 상태이다. */
    SKIPPED
}
