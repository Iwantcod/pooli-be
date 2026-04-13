package com.pooli.traffic.service.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.payload.InFlightDedupeDeleteOutboxPayload;

import lombok.RequiredArgsConstructor;

/**
 * in-flight dedupe key 삭제 요청을 Outbox로 적재하는 서비스입니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficInFlightDedupeDeleteOutboxService {

    private final RedisOutboxRecordService redisOutboxRecordService;

    /**
     * traceId 기준 dedupe key 삭제 요청 Outbox를 PENDING 상태로 생성합니다.
     *
     * @return 생성된 Outbox ID
     */
    public long createPending(String traceId, String sourceRecordId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }

        String normalizedTraceId = traceId.trim();
        // TRAFFIC_REDIS_OUTBOX.uuid 컬럼은 멱등키로 사용하므로 traceId로 통일합니다.
        String uuid = normalizedTraceId;
        InFlightDedupeDeleteOutboxPayload payload = InFlightDedupeDeleteOutboxPayload.builder()
                .uuid(uuid)
                .sourceRecordId(sourceRecordId)
                .requestedAtEpochMillis(System.currentTimeMillis())
                .build();

        return redisOutboxRecordService.createPending(
                OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY,
                payload,
                uuid
        );
    }
}
