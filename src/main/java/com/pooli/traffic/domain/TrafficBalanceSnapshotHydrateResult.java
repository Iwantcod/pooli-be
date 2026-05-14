package com.pooli.traffic.domain;

/**
 * Redis 잔량 snapshot hydrate 시도 결과입니다.
 */
public record TrafficBalanceSnapshotHydrateResult(Status status) {

    /**
     * Redis snapshot 적재가 완료되어 호출자가 Redis amount를 다시 읽어도 되는 상태입니다.
     */
    public static TrafficBalanceSnapshotHydrateResult hydrated() {
        return new TrafficBalanceSnapshotHydrateResult(Status.HYDRATED);
    }

    /**
     * lock 경합, stale refresh 미수렴 등 재시도하면 해결될 수 있는 미준비 상태입니다.
     */
    public static TrafficBalanceSnapshotHydrateResult notReady() {
        return new TrafficBalanceSnapshotHydrateResult(Status.NOT_READY);
    }

    /**
     * RDB source snapshot 자체가 없어 stream hydrate에서는 실패 사유로 남겨야 하는 상태입니다.
     */
    public static TrafficBalanceSnapshotHydrateResult snapshotNotFound() {
        return new TrafficBalanceSnapshotHydrateResult(Status.SNAPSHOT_NOT_FOUND);
    }

    /**
     * 요청 월이 RDB refresh 월보다 과거라 현재 source로 안전하게 hydrate할 수 없는 상태입니다.
     */
    public static TrafficBalanceSnapshotHydrateResult staleTargetMonth() {
        return new TrafficBalanceSnapshotHydrateResult(Status.STALE_TARGET_MONTH);
    }

    /**
     * owner 식별자나 대상 월이 유효하지 않아 hydrate를 시도하지 않은 상태입니다.
     */
    public static TrafficBalanceSnapshotHydrateResult invalidOwner() {
        return new TrafficBalanceSnapshotHydrateResult(Status.INVALID_OWNER);
    }

    /**
     * 호출자가 후속 Redis 재조회나 Lua 재시도를 진행할 수 있는 성공 여부를 반환합니다.
     */
    public boolean isHydrated() {
        return status == Status.HYDRATED;
    }

    /**
     * stream 처리에서 ERROR 결과와 metric 사유로 변환해야 하는 복구 불가능 상태인지 판단합니다.
     */
    public boolean isInvalidForStreamHydrate() {
        return status == Status.SNAPSHOT_NOT_FOUND || status == Status.STALE_TARGET_MONTH;
    }

    /**
     * stream hydrate 실패 사유 문자열을 반환하고, 실패로 취급하지 않는 상태는 null을 반환합니다.
     */
    public String failureReason() {
        return switch (status) {
            case SNAPSHOT_NOT_FOUND -> "SNAPSHOT_NOT_FOUND";
            case STALE_TARGET_MONTH -> "STALE_TARGET_MONTH";
            default -> null;
        };
    }

    public enum Status {
        HYDRATED,
        NOT_READY,
        SNAPSHOT_NOT_FOUND,
        STALE_TARGET_MONTH,
        INVALID_OWNER
    }
}
