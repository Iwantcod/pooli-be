package com.pooli.traffic.service.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.payload.InFlightDedupeDeleteOutboxPayload;
import com.pooli.traffic.service.retry.TrafficInFlightDedupeDeleteRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficInFlightDedupeDeleteRetryInvoker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * in-flight dedupe key 삭제 요청을 Outbox로 적재하는 서비스입니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficInFlightDedupeDeleteOutboxService {

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficInFlightDedupeDeleteRetryInvoker trafficInFlightDedupeDeleteRetryInvoker;

    /**
     * traceId 기준 dedupe key 삭제 요청 Outbox를 PENDING 상태로 생성합니다.
     * 생성 직후 기존 처리 흐름에서 동기적으로 즉시 삭제를 시도합니다.
     * - 정상 처리 1회
     * - 정상 처리 실패 시 즉시 재시도 최대 3회
     * - 즉시 재시도 횟수는 Outbox retry_count에 반영하지 않습니다.
     *
     * @return 생성된 Outbox ID
     */
    public long createPending(String traceId, String sourceRecordId) {
        // [1] outbox 공통 식별자(traceId) 유효성 검증: blank는 즉시 차단합니다.
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }

        // [2] traceId를 trim 정규화해 payload/DB 저장/로그에서 동일 식별자를 사용합니다.
        String normalizedTraceId = traceId.trim();

        // [3] 삭제 요청 이벤트 payload를 구성합니다.
        InFlightDedupeDeleteOutboxPayload payload = InFlightDedupeDeleteOutboxPayload.builder()
                .uuid(normalizedTraceId)
                .sourceRecordId(sourceRecordId)
                .requestedAtEpochMillis(System.currentTimeMillis())
                .build();

        // [4] Outbox PENDING 레코드를 먼저 저장해 스케줄러 재시도 기반의 복구 경로를 확보합니다.
        long outboxId = redisOutboxRecordService.createPending(
                OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY,
                payload,
                normalizedTraceId
        );

        // [5] 즉시 삭제는 Retry 어댑터에 위임합니다. (초기 1회 + 재시도 최대 3회)
        TrafficInFlightDedupeDeleteRetryExecutionResult retryExecutionResult =
                trafficInFlightDedupeDeleteRetryInvoker.delete(normalizedTraceId);

        // [6] 즉시 삭제 성공 시 SUCCESS로 전이합니다.
        //     성공 전 실패가 있었다면 복구 로그를 남겨 운영 추적성을 확보합니다.
        if (retryExecutionResult.succeeded()) {
            if (retryExecutionResult.failedAttemptCount() > 0) {
                log.info(
                        "traffic_inflight_dedupe_delete_immediate_retry_recovered outboxId={} traceId={} retryAttempt={}",
                        outboxId,
                        normalizedTraceId,
                        retryExecutionResult.failedAttemptCount()
                );
            }
            redisOutboxRecordService.markSuccess(outboxId);
        } else {
            // [7] 즉시 재시도 소진 시 FAIL로만 전이하고 retry_count는 증가시키지 않습니다.
            //     이후 재처리는 Outbox 스케줄러 표준 경로가 담당합니다.
            RuntimeException lastFailure = retryExecutionResult.lastFailure();
            log.warn(
                    "traffic_inflight_dedupe_delete_immediate_retry_exhausted outboxId={} traceId={} maxRetryCount={} reason={}",
                    outboxId,
                    normalizedTraceId,
                    Math.max(0, retryExecutionResult.attemptCount() - 1),
                    classifyReason(lastFailure),
                    lastFailure
            );
            // 즉시 재시도 실패는 FAIL로만 전이하고 retry_count는 증가시키지 않는다.
            redisOutboxRecordService.markFail(outboxId);
        }
        // [8] 호출부(consumer)는 outboxId를 사용해 처리 순서 검증/추적을 이어갑니다.
        return outboxId;
    }

    private String classifyReason(RuntimeException exception) {
        if (exception == null) {
            return "UNKNOWN";
        }
        String className = exception.getClass().getSimpleName();
        return (className == null || className.isBlank()) ? "UNKNOWN" : className;
    }
}
