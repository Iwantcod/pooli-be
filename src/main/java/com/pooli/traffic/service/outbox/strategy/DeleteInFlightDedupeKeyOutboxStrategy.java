package com.pooli.traffic.service.outbox.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.InFlightDedupeDeleteOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * in-flight dedupe key 삭제 Outbox 재시도 전략입니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class DeleteInFlightDedupeKeyOutboxStrategy implements OutboxEventRetryStrategy {

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

    @Override
    public OutboxEventType supports() {
        return OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY;
    }

    @Override
    public OutboxRetryResult execute(RedisOutboxRecord record) {
        // 1) Outbox payload를 역직렬화해 재시도 대상 traceId를 추출한다.
        InFlightDedupeDeleteOutboxPayload payload =
                redisOutboxRecordService.readPayload(record, InFlightDedupeDeleteOutboxPayload.class);

        // 2) traceId가 비어 있으면 재처리할 대상이 없으므로 FAIL로 남겨 운영자가 원인을 확인할 수 있게 한다.
        String traceId = payload == null ? null : payload.getUuid();
        if (traceId == null || traceId.isBlank()) {
            log.error(
                    "traffic_outbox_inflight_dedupe_delete_payload_invalid outboxId={} reason=missing_trace_id",
                    record.getId()
            );
            return OutboxRetryResult.FAIL;
        }

        try {
            // 3) dedupe key 삭제를 수행한다. 키가 이미 없더라도 delete는 멱등 성공으로 간주한다.
            trafficInFlightDedupeService.delete(traceId);
            return OutboxRetryResult.SUCCESS;
        } catch (RuntimeException e) {
            // 4) Redis 예외는 스케줄러가 retry_count를 증가시키며 재시도하도록 FAIL을 반환한다.
            return OutboxRetryResult.FAIL;
        }
    }
}
