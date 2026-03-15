package com.pooli.traffic.service.outbox.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.RefillOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REFILL 재시도 전략입니다.
 */
@Slf4j
@Component
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class RefillOutboxStrategy implements OutboxEventRetryStrategy {

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;
    private final TrafficQuotaCacheService trafficQuotaCacheService;

    @Override
    public OutboxEventType supports() {
        return OutboxEventType.REFILL;
    }

    @Override
    public OutboxRetryResult execute(RedisOutboxRecord record) {
        RefillOutboxPayload payload = redisOutboxRecordService.readPayload(record, RefillOutboxPayload.class);
        if (payload.getUuid() == null || payload.getUuid().isBlank()) {
            log.error("traffic_outbox_refill_payload_invalid outboxId={} reason=missing_uuid", record.getId());
            return OutboxRetryResult.FAIL;
        }

        try {
            boolean idempotencyRegistered = trafficRefillOutboxSupportService.tryRegisterIdempotency(payload.getUuid());
            if (!idempotencyRegistered) {
                return OutboxRetryResult.SUCCESS;
            }
        } catch (RuntimeException e) {
            RuntimeException unwrapped = trafficRefillOutboxSupportService.unwrapRuntimeException(e);
            if (trafficRefillOutboxSupportService.isConnectionFailure(unwrapped)) {
                trafficRefillOutboxSupportService.restoreClaimedAmount(payload);
                return OutboxRetryResult.SUCCESS;
            }
            return OutboxRetryResult.FAIL;
        }

        long refillAmount = payload.getActualRefillAmount() == null ? 0L : payload.getActualRefillAmount();
        if (refillAmount <= 0) {
            return OutboxRetryResult.SUCCESS;
        }

        try {
            String balanceKey = trafficRefillOutboxSupportService.resolveBalanceKey(payload);
            long expireAtEpochSeconds = trafficRefillOutboxSupportService.resolveMonthlyExpireAt(payload);
            trafficQuotaCacheService.refillBalance(balanceKey, refillAmount, expireAtEpochSeconds);
            return OutboxRetryResult.SUCCESS;
        } catch (RuntimeException e) {
            RuntimeException unwrapped = trafficRefillOutboxSupportService.unwrapRuntimeException(e);
            if (trafficRefillOutboxSupportService.isConnectionFailure(unwrapped)) {
                trafficRefillOutboxSupportService.restoreClaimedAmount(payload);
                return OutboxRetryResult.SUCCESS;
            }
            return OutboxRetryResult.FAIL;
        }
    }
}
