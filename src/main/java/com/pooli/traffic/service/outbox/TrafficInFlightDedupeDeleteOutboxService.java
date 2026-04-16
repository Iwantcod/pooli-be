package com.pooli.traffic.service.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.payload.InFlightDedupeDeleteOutboxPayload;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;

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

    private static final int MAX_IMMEDIATE_RETRY_COUNT = 3;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long immediateRetryBackoffMs = 50L;

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

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
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }

        String normalizedTraceId = traceId.trim();
        InFlightDedupeDeleteOutboxPayload payload = InFlightDedupeDeleteOutboxPayload.builder()
                .uuid(normalizedTraceId)
                .sourceRecordId(sourceRecordId)
                .requestedAtEpochMillis(System.currentTimeMillis())
                .build();

        long outboxId = redisOutboxRecordService.createPending(
                OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY,
                payload,
                normalizedTraceId
        );

        boolean deleteSucceeded = attemptDeleteImmediately(normalizedTraceId, outboxId);
        if (deleteSucceeded) {
            redisOutboxRecordService.markSuccess(outboxId);
        } else {
            // 즉시 재시도 실패는 FAIL로만 전이하고 retry_count는 증가시키지 않는다.
            redisOutboxRecordService.markFail(outboxId);
        }
        return outboxId;
    }

    private boolean attemptDeleteImmediately(String traceId, long outboxId) {
        try {
            // 1) 첫 시도를 즉시 수행한다. 이미 삭제된 키도 멱등 성공으로 취급한다.
            trafficInFlightDedupeService.delete(traceId);
            return true;
        } catch (RuntimeException firstFailure) {
            // 2) 첫 시도 실패 시 사유를 남기고 즉시 재시도 루프로 진입한다.
            log.warn(
                    "traffic_inflight_dedupe_delete_initial_attempt_failed outboxId={} traceId={} reason={}",
                    outboxId,
                    traceId,
                    classifyReason(firstFailure)
            );
        }

        for (int retryAttempt = 1; retryAttempt <= MAX_IMMEDIATE_RETRY_COUNT; retryAttempt++) {
            // 3) 재시도 전 지수 백오프(50/100/200ms)로 짧게 대기한다.
            if (!sleepImmediateRetryBackoff(retryAttempt, outboxId, traceId)) {
                // 인터럽트가 들어오면 재시도를 중단하고 실패로 종료한다.
                return false;
            }
            try {
                // 4) 재시도 중 하나라도 성공하면 즉시 성공 처리한다.
                trafficInFlightDedupeService.delete(traceId);
                log.info(
                        "traffic_inflight_dedupe_delete_immediate_retry_recovered outboxId={} traceId={} retryAttempt={}",
                        outboxId,
                        traceId,
                        retryAttempt
                );
                return true;
            } catch (RuntimeException e) {
                if (retryAttempt == MAX_IMMEDIATE_RETRY_COUNT) {
                    // 5) 재시도 3회까지 모두 실패하면 즉시 재시도 구간을 실패로 종료한다.
                    log.warn(
                            "traffic_inflight_dedupe_delete_immediate_retry_exhausted outboxId={} traceId={} maxRetryCount={} reason={}",
                            outboxId,
                            traceId,
                            MAX_IMMEDIATE_RETRY_COUNT,
                            classifyReason(e),
                            e
                    );
                    return false;
                }
                // 다음 재시도를 위해 현재 실패를 기록한다.
                log.warn(
                        "traffic_inflight_dedupe_delete_immediate_retry_failed outboxId={} traceId={} retryAttempt={} reason={}",
                        outboxId,
                        traceId,
                        retryAttempt,
                        classifyReason(e)
                );
            }
        }
        return false;
    }

    /**
     * 즉시 재시도 간격을 지수 백오프로 제어합니다. (50/100/200ms)
     */
    private boolean sleepImmediateRetryBackoff(int retryAttempt, long outboxId, String traceId) {
        // retryAttempt(1..3) => 50/100/200ms
        long delayMs = TrafficRetryBackoffSupport.resolveDelayMs(immediateRetryBackoffMs, retryAttempt);
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_inflight_dedupe_delete_immediate_retry_interrupted outboxId={} traceId={} retryAttempt={}",
                    outboxId,
                    traceId,
                    retryAttempt
            );
            return false;
        }
    }

    private String classifyReason(RuntimeException exception) {
        if (exception == null) {
            return "UNKNOWN";
        }
        String className = exception.getClass().getSimpleName();
        return (className == null || className.isBlank()) ? "UNKNOWN" : className;
    }
}
