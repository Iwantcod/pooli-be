package com.pooli.traffic.domain.restore;

/**
 * Redis 장애 복구 target row의 worker 처리 상태이다.
 * worker는 PENDING, RETRYABLE, lease timeout이 지난 PROCESSING row만 선점 대상으로 삼는다.
 */
public enum TrafficRestoreTargetStatus {
    /** 아직 worker가 선점하지 않은 target row이다. */
    PENDING(true, false),

    /** worker가 선점하여 Redis 복구 작업을 수행 중인 row이다. */
    PROCESSING(false, false),

    /** Redis 복구 작업을 정상 완료한 terminal 상태이다. */
    DONE(false, true),

    /** 재시도 가능한 실패로 다시 선점될 수 있는 상태이다. */
    RETRYABLE(true, false),

    /** 재시도 한도를 초과했거나 자동 복구가 불가능한 terminal 실패 상태이다. */
    FAILED(false, true);

    private final boolean claimable;
    private final boolean terminal;

    TrafficRestoreTargetStatus(boolean claimable, boolean terminal) {
        this.claimable = claimable;
        this.terminal = terminal;
    }

    /**
     * worker가 신규 claim 대상으로 볼 수 있는 상태인지 반환한다.
     */
    public boolean isClaimable() {
        return claimable;
    }

    /**
     * 추가 worker 처리가 끝난 최종 상태인지 반환한다.
     */
    public boolean isTerminal() {
        return terminal;
    }
}
