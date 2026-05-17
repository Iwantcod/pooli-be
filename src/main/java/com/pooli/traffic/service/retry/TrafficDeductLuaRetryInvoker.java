package com.pooli.traffic.service.retry;

import org.springframework.dao.DataAccessException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 차감 Lua Redis 접근부의 retryable 인프라 예외 재시도를 전담하는 어댑터입니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficDeductLuaRetryInvoker {

    private static final String RETRY_LAST_FAILURE_CONTEXT_KEY = "traffic_deduct_lua_retry_last_failure";

    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    /**
     * 차감 Lua 실행을 retryable 인프라 예외 기준으로 즉시 재시도합니다.
     * non-retryable 예외는 즉시 결과로 반환해 호출부가 기존 단계 예외 규칙을 유지하도록 합니다.
     */
    @Retryable(
            retryFor = RetryableInfrastructureException.class,
            maxAttemptsExpression = "#{${app.traffic.deduct.redis-retry.max-attempts:3} + 1}",
            backoff = @Backoff(
                    delayExpression = "${app.traffic.deduct.redis-retry.backoff-ms:50}",
                    multiplier = 2.0
            )
    )
    public TrafficDeductLuaRetryExecutionResult execute(TrafficDeductLuaRetryOperation operation) {
        try {
            // [1] 호출부가 전달한 실제 Lua 작업을 실행합니다.
            TrafficLuaExecutionResult luaExecutionResult = operation.execute();
            // [2] 현재 retry 컨텍스트의 누적 시도 횟수를 계산합니다.
            int attemptCount = resolveAttemptCount(1);
            // [3] 성공 결과를 DTO로 반환하고, 이전 retryable 실패가 있으면 함께 전달합니다.
            return TrafficDeductLuaRetryExecutionResult.success(
                    luaExecutionResult,
                    attemptCount,
                    resolveLastRetryableFailure()
            );
        } catch (ApplicationException | DataAccessException exception) {
            // [4] 분류기가 cause chain 전체를 확인하므로 wrapper 예외를 그대로 전달합니다.
            int attemptCount = resolveAttemptCount(1);

            // [5] non-retryable이면 Retry 프레임워크로 던지지 않고 즉시 실패 DTO를 반환합니다.
            if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)) {
                return TrafficDeductLuaRetryExecutionResult.nonRetryableFailure(
                        exception,
                        attemptCount,
                        resolveLastRetryableFailure()
                );
            }

            // [6] retryable 실패면 마지막 실패를 컨텍스트에 저장하고
            //     RetryableInfrastructureException으로 래핑해 @Retryable 재시도를 트리거합니다.
            rememberLastRetryableFailure(exception);
            throw new RetryableInfrastructureException(exception, attemptCount);
        }
    }

    /**
     * retry 소진 시 마지막 retryable 예외를 포함한 결과를 반환합니다.
     */
    @Recover
    public TrafficDeductLuaRetryExecutionResult recover(
            RetryableInfrastructureException exception,
            TrafficDeductLuaRetryOperation operation
    ) {
        // [1] Retry 프레임워크가 전달한 마지막 attempt 정보를 기준으로 최종 시도 횟수를 확정합니다.
        int attemptCount = resolveAttemptCount(exception.attemptCount());
        // [2] 컨텍스트에 저장된 마지막 retryable 실패를 우선 사용하고, 없으면 recover 인자로 넘어온 실패를 사용합니다.
        RuntimeException lastFailure = resolveLastRetryableFailure();
        RuntimeException failureToReport = lastFailure == null ? exception.lastFailure() : lastFailure;
        // [3] retry 소진 최종 실패 DTO를 호출부로 반환합니다.
        return TrafficDeductLuaRetryExecutionResult.retryableFailure(failureToReport, attemptCount);
    }

    private void rememberLastRetryableFailure(RuntimeException failure) {
        // [1] 현재 스레드의 RetryContext를 조회합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        // [2] 컨텍스트가 있으면 마지막 retryable 실패를 attribute로 저장합니다.
        if (retryContext != null) {
            retryContext.setAttribute(RETRY_LAST_FAILURE_CONTEXT_KEY, failure);
        }
    }

    private RuntimeException resolveLastRetryableFailure() {
        // [1] 현재 스레드의 RetryContext를 조회합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        // [2] retry 경계 밖이면 저장된 실패 이력이 없으므로 null을 반환합니다.
        if (retryContext == null) {
            return null;
        }
        // [3] 마지막 retryable 실패 attribute를 읽고, 타입이 맞을 때만 반환합니다.
        Object attribute = retryContext.getAttribute(RETRY_LAST_FAILURE_CONTEXT_KEY);
        if (attribute instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (attribute != null) {
            log.info("traffic_deduct_lua_retry_last_failure attribute is not a RuntimeException.");
        }
        return null;
    }

    private int resolveAttemptCount(int fallbackAttemptCount) {
        // [1] RetryContext가 있으면 retryCount(0-based)에 +1 하여 사람이 읽는 시도 횟수(1-based)로 변환합니다.
        // [2] RetryContext가 없으면 fallback 값을 최소 1로 보정해 반환합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return Math.max(1, fallbackAttemptCount);
        }
        return retryContext.getRetryCount() + 1;
    }

    /**
     * retryable 인프라 예외를 Retry 프레임워크 경계로 전달하기 위한 내부 래퍼입니다.
     */
    private static final class RetryableInfrastructureException extends RuntimeException {

        private final RuntimeException lastFailure;
        private final int attemptCount;

        private RetryableInfrastructureException(RuntimeException lastFailure, int attemptCount) {
            super(lastFailure == null ? "retryable_infrastructure_failure" : lastFailure.getMessage(), lastFailure);
            this.lastFailure = lastFailure;
            this.attemptCount = Math.max(1, attemptCount);
        }

        private RuntimeException lastFailure() {
            return lastFailure;
        }

        private int attemptCount() {
            return attemptCount;
        }
    }
}
