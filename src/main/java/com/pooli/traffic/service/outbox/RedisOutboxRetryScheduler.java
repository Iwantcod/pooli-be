package com.pooli.traffic.service.outbox;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.RefillOutboxPayload;
import com.pooli.traffic.service.outbox.strategy.OutboxEventRetryStrategy;
import com.pooli.traffic.service.outbox.strategy.OutboxRetryStrategyRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Outbox FAIL/PENDING/PROCESSING 레코드를 주기적으로 재시도하는 스케줄러입니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class RedisOutboxRetryScheduler {

    @Value("${app.traffic.outbox.retry.batch-size:100}")
    private int batchSize;

    @Value("${app.traffic.outbox.retry.pending-delay-seconds:60}")
    private int pendingDelaySeconds;

    @Value("${app.traffic.outbox.retry.processing-stuck-seconds:60}")
    private int processingStuckSeconds;

    @Value("${app.traffic.outbox.retry.max-retry-count:10}")
    private int maxRetryCount;

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final OutboxRetryStrategyRegistry outboxRetryStrategyRegistry;
    private final TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;

    @Scheduled(fixedDelayString = "${app.traffic.outbox.retry.fixed-delay-ms:5000}")
    public void runRetryCycle() {
        int normalizedBatchSize = Math.max(1, batchSize);
        int normalizedPendingDelaySeconds = Math.max(1, pendingDelaySeconds);
        int normalizedProcessingStuckSeconds = Math.max(1, processingStuckSeconds);

        List<RedisOutboxRecord> candidates = redisOutboxRecordService.lockRetryCandidatesAndMarkProcessing(
                normalizedBatchSize,
                normalizedPendingDelaySeconds,
                normalizedProcessingStuckSeconds
        );

        for (RedisOutboxRecord candidate : candidates) {
            if (candidate.getId() == null || candidate.getEventType() == null) {
                continue;
            }

            try {
                processOne(candidate);
            } catch (RuntimeException e) {
                log.error(
                        "traffic_outbox_retry_cycle_failed outboxId={} eventType={}",
                        candidate.getId(),
                        candidate.getEventType(),
                        e
                );
                redisOutboxRecordService.markFailWithRetryIncrement(candidate.getId());
            }
        }
    }

    private void processOne(RedisOutboxRecord record) {
        int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
        if (retryCount >= maxRetryCount) {
            handleRetryCapExceeded(record, retryCount);
            return;
        }

        OutboxEventRetryStrategy strategy = outboxRetryStrategyRegistry.get(record.getEventType());
        OutboxRetryResult retryResult = strategy.execute(record);
        if (retryResult == OutboxRetryResult.SUCCESS) {
            redisOutboxRecordService.markSuccess(record.getId());
            if (record.getEventType() == OutboxEventType.REFILL) {
                RefillOutboxPayload payload = redisOutboxRecordService.readPayload(record, RefillOutboxPayload.class);
                clearIdempotencyBestEffort(record.getId(), payload);
            }
            return;
        }

        redisOutboxRecordService.markFailWithRetryIncrement(record.getId());
    }

    /**
     * retry_count 상한 초과 레코드를 처리합니다.
     * - 재시도는 수행하지 않습니다.
     * - REFILL은 DB 반납을 1회 수행하고 REVERT 터미널 상태로 종료합니다.
     * - 비-REFILL은 FINAL_FAIL 터미널 상태로 종료합니다.
     */
    private void handleRetryCapExceeded(RedisOutboxRecord record, int retryCount) {
        log.error(
                "traffic_outbox_retry_cap_exceeded outboxId={} eventType={} retryCount={} reason=max_retry_exceeded",
                record.getId(),
                record.getEventType(),
                retryCount
        );

        if (record.getEventType() == OutboxEventType.REFILL) {
            RefillOutboxPayload payload = redisOutboxRecordService.readPayload(record, RefillOutboxPayload.class);
            trafficRefillOutboxSupportService.compensateRefillOnce(record.getId(), payload);
            return;
        }

        redisOutboxRecordService.markFinalFail(record.getId());
    }

    /**
     * REFILL 성공 이후 멱등키 정리는 best-effort로 수행합니다.
     * 정리 실패는 후처리 오류이므로 Outbox 성공 상태를 되돌리지 않습니다.
     */
    private void clearIdempotencyBestEffort(Long outboxId, RefillOutboxPayload payload) {
        if (payload == null || payload.getUuid() == null || payload.getUuid().isBlank()) {
            return;
        }
        try {
            trafficRefillOutboxSupportService.clearIdempotency(payload.getUuid());
        } catch (RuntimeException e) {
            log.warn(
                    "traffic_outbox_refill_idempotency_clear_failed_but_success_preserved outboxId={} uuid={}",
                    outboxId,
                    payload.getUuid(),
                    e
            );
        }
    }
}
