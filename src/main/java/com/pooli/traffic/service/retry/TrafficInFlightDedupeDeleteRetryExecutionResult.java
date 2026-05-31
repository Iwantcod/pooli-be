package com.pooli.traffic.service.retry;

/**
 * in-flight dedupe key 삭제 retry 실행 결과를 호출부에 전달하는 불변 DTO입니다.
 */
public record TrafficInFlightDedupeDeleteRetryExecutionResult(
        boolean succeeded,
        RuntimeException lastFailure,
        int attemptCount,
        int failedAttemptCount
) {
    public static TrafficInFlightDedupeDeleteRetryExecutionResult success(
            int attemptCount,
            RuntimeException lastFailure
    ) {
        int normalizedAttemptCount = normalizeAttemptCount(attemptCount);
        int normalizedFailedAttemptCount = Math.max(0, normalizedAttemptCount - 1);
        return new TrafficInFlightDedupeDeleteRetryExecutionResult(
                true,
                lastFailure,
                normalizedAttemptCount,
                normalizedFailedAttemptCount
        );
    }

    public static TrafficInFlightDedupeDeleteRetryExecutionResult failure(
            RuntimeException lastFailure,
            int attemptCount
    ) {
        int normalizedAttemptCount = normalizeAttemptCount(attemptCount);
        return new TrafficInFlightDedupeDeleteRetryExecutionResult(
                false,
                lastFailure,
                normalizedAttemptCount,
                normalizedAttemptCount
        );
    }

    private static int normalizeAttemptCount(int attemptCount) {
        return Math.max(1, attemptCount);
    }
}
