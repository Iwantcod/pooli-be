package com.pooli.traffic.domain.batch;

/**
 * LINE_DAILY_BATCH_JOB의 수명주기 상태이다.
 * worker는 usage sync batch가 RUNNING일 때만 target row 처리를 시작한다.
 */
public enum LineDailyBatchStatus {
    /** 생성되었지만 아직 실행 가능한 상태가 아닌 batch이다. */
    PENDING,

    /** manager 또는 worker가 처리 중인 batch이다. */
    RUNNING,

    /** 완료 조건을 만족해 더 이상 자동 처리가 필요 없는 batch이다. */
    COMPLETED,

    /** 자동 복구가 불가능해 운영자 확인이 필요한 실패 상태이다. */
    FAILED,

    /** 운영자가 더 이상 재개하지 않기로 판단한 종료 상태이다. */
    ABANDONED
}
