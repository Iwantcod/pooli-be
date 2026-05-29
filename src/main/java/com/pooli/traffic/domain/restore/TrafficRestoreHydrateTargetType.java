package com.pooli.traffic.domain.restore;

/**
 * 복구 phase 0에서 hydrate할 Redis target 종류이다.
 */
public enum TrafficRestoreHydrateTargetType {
    /** line 개인 잔량 Redis key를 hydrate하는 target이다. */
    LINE,

    /** family 공유 잔량 Redis key를 hydrate하는 target이다. */
    FAMILY,

    /** 전역 policy Redis key를 hydrate하는 target이다. */
    GLOBAL_POLICY
}
