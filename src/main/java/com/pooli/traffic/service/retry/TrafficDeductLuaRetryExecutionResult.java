package com.pooli.traffic.service.retry;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;

/**
 * 차감 Lua retry 실행 결과를 호출부로 전달하는 불변 DTO입니다.
 */
public record TrafficDeductLuaRetryExecutionResult(
        TrafficLuaExecutionResult luaResult,
        RuntimeException terminalFailure,
        RuntimeException lastRetryableFailure,
        boolean retryableInfrastructureFailure,
        int attemptCount,
        int failedAttemptCount
) {
    public static TrafficDeductLuaRetryExecutionResult success(
            TrafficLuaExecutionResult luaResult,
            int attemptCount,
            RuntimeException lastFailure
    ) {
        int normalizedAttemptCount = normalizeAttemptCount(attemptCount);
        int normalizedFailedAttemptCount = Math.max(0, normalizedAttemptCount - 1);
        return new TrafficDeductLuaRetryExecutionResult(
                luaResult,
                null,
                lastFailure,
                false,
                normalizedAttemptCount,
                normalizedFailedAttemptCount
        );
    }

    public static TrafficDeductLuaRetryExecutionResult nonRetryableFailure(
            RuntimeException terminalFailure,
            int attemptCount,
            RuntimeException lastRetryableFailure
    ) {
        int normalizedAttemptCount = normalizeAttemptCount(attemptCount);
        return new TrafficDeductLuaRetryExecutionResult(
                null,
                terminalFailure,
                lastRetryableFailure,
                false,
                normalizedAttemptCount,
                Math.max(0, normalizedAttemptCount - 1)
        );
    }

    public static TrafficDeductLuaRetryExecutionResult retryableFailure(
            RuntimeException lastRetryableFailure,
            int attemptCount
    ) {
        int normalizedAttemptCount = normalizeAttemptCount(attemptCount);
        return new TrafficDeductLuaRetryExecutionResult(
                null,
                lastRetryableFailure,
                lastRetryableFailure,
                true,
                normalizedAttemptCount,
                normalizedAttemptCount
        );
    }

    public boolean hasSuccessResult() {
        return luaResult != null;
    }

    private static int normalizeAttemptCount(int attemptCount) {
        return Math.max(1, attemptCount);
    }
}
