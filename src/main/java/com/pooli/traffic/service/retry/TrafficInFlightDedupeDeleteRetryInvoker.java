package com.pooli.traffic.service.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

import lombok.RequiredArgsConstructor;

/**
 * in-flight dedupe key 삭제 Redis 접근부의 즉시 재시도를 전담하는 어댑터입니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficInFlightDedupeDeleteRetryInvoker {

    private static final String RETRY_LAST_FAILURE_CONTEXT_KEY = "traffic_inflight_dedupe_delete_last_failure";
    private static final String RETRY_TRACE_ID_CONTEXT_KEY = "traffic_inflight_dedupe_delete_trace_id";

    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

    /**
     * dedupe key 삭제를 retryable/non-retryable 구분 없이 RuntimeException 기준으로 즉시 재시도합니다.
     */
    @Retryable(
            retryFor = RuntimeException.class,
            maxAttemptsExpression = "#{${app.traffic.deduct.redis-retry.max-attempts:3} + 1}",
            backoff = @Backoff(
                    delayExpression = "${app.traffic.deduct.redis-retry.backoff-ms:50}",
                    multiplier = 2.0
            )
    )
    public TrafficInFlightDedupeDeleteRetryExecutionResult delete(String traceId) {
        // [0] 재시도 체인 전체에서 동일 traceId를 참조할 수 있도록 RetryContext에 저장합니다.
        rememberTraceId(traceId);
        try {
            // [1] 실제 dedupe key 삭제를 수행합니다. (idempotent delete)
            trafficInFlightDedupeService.delete(traceId);
            // [2] retry 컨텍스트를 기반으로 누적 시도 횟수를 계산합니다.
            int attemptCount = resolveAttemptCount(1);
            // [3] 성공 DTO를 반환하며, 성공 전 실패 이력이 있으면 함께 전달합니다.
            return TrafficInFlightDedupeDeleteRetryExecutionResult.success(
                    attemptCount,
                    resolveLastFailure()
            );
        } catch (RuntimeException exception) {
            // [4] recover 단계에서 원인을 재사용할 수 있도록 마지막 실패를 컨텍스트에 저장합니다.
            rememberLastFailure(exception);
            // [5] 예외를 그대로 재던져 @Retryable 재시도 체인으로 넘깁니다.
            throw exception;
        }
    }

    /**
     * retry 소진 시 마지막 예외를 포함한 실패 결과를 반환합니다.
     */
    @Recover
    public TrafficInFlightDedupeDeleteRetryExecutionResult recover(RuntimeException exception, String traceId) {
        // [0] recover 진입 시점에도 traceId를 RetryContext에 보강해 후속 로그에서 참조 가능하게 합니다.
        rememberTraceId(traceId);
        // [1] 최종 시도 횟수를 확정합니다.
        int attemptCount = resolveAttemptCount(1);
        // [2] 컨텍스트 저장 실패를 우선 사용하고, 없으면 recover 인자로 넘어온 예외를 사용합니다.
        RuntimeException lastFailure = resolveLastFailure();
        RuntimeException failureToReport = lastFailure == null ? exception : lastFailure;
        // [3] retry 소진 실패 DTO를 호출부로 반환합니다.
        return TrafficInFlightDedupeDeleteRetryExecutionResult.failure(failureToReport, attemptCount);
    }

    /**
     * 마지막 실패 예외를 현재 RetryContext attribute에 저장합니다.
     *
     * <p>목적:
     * <br>- `@Recover` 단계에서 가장 최근 실패 원인을 재사용하기 위함입니다.
     */
    private void rememberLastFailure(RuntimeException failure) {
        // [1] 현재 스레드의 RetryContext를 조회합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        // [2] 컨텍스트가 있으면 마지막 실패 예외를 attribute로 저장합니다.
        if (retryContext != null) {
            retryContext.setAttribute(RETRY_LAST_FAILURE_CONTEXT_KEY, failure);
        }
    }

    /**
     * 현재 RetryContext에 저장된 마지막 실패 예외를 조회합니다.
     *
     * <p>동작:
     * <br>- 컨텍스트가 없으면 `null`을 반환합니다.
     * <br>- attribute가 `RuntimeException`이면 그대로 반환합니다.
     * <br>- 타입이 다르면 traceId와 함께 로그를 남기고 `null`을 반환합니다.
     */
    private RuntimeException resolveLastFailure() {
        // [1] 현재 스레드의 RetryContext를 조회합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        // [2] retry 경계 밖이면 실패 이력이 없으므로 null을 반환합니다.
        if (retryContext == null) {
            return null;
        }
        // [3] 저장된 마지막 실패 attribute를 읽고, RuntimeException 타입일 때만 반환합니다.
        Object attribute = retryContext.getAttribute(RETRY_LAST_FAILURE_CONTEXT_KEY);
        if (attribute instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (attribute != null) {
            log.info(
                    "traffic_inflight_dedupe_delete_last_failure attribute is not a RuntimeException. traceId={}",
                    resolveTraceIdFromContext()
            );
        }
        return null;
    }

    /**
     * 재시도 체인 전 구간에서 동일 traceId를 참조할 수 있도록 RetryContext에 저장합니다.
     *
     * @param traceId 요청 단위 식별자
     */
    private void rememberTraceId(String traceId) {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext != null && traceId != null && !traceId.isBlank()) {
            retryContext.setAttribute(RETRY_TRACE_ID_CONTEXT_KEY, traceId.trim());
        }
    }

    /**
     * RetryContext에서 traceId를 조회합니다.
     *
     * @return traceId가 없으면 `"unknown"`을 반환합니다.
     */
    private String resolveTraceIdFromContext() {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return "unknown";
        }
        Object attribute = retryContext.getAttribute(RETRY_TRACE_ID_CONTEXT_KEY);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return "unknown";
    }

    /**
     * 현재 retry 횟수를 사람이 읽는 1-based 값으로 반환합니다.
     *
     * @param fallbackAttemptCount RetryContext가 없을 때 사용할 기본값
     * @return 최소 1 이상 시도 횟수
     */
    private int resolveAttemptCount(int fallbackAttemptCount) {
        // [1] retryCount(0-based)를 사람이 읽는 시도 횟수(1-based)로 맞춥니다.
        // [2] retry 컨텍스트가 없으면 fallback 값을 최소 1로 보정해 반환합니다.
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return Math.max(1, fallbackAttemptCount);
        }
        return retryContext.getRetryCount() + 1;
    }
}
