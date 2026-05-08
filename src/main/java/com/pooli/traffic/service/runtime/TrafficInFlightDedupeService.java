package com.pooli.traffic.service.runtime;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntry;
import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntryResult;
import com.pooli.traffic.domain.enums.TrafficInFlightState;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * traceId 기준 in-flight 멱등 hash를 관리하는 서비스입니다.
 * 멱등키는 Redis hash(`processed_individual_data`, `processed_shared_data`, `processed_qos_data`, `retry_count`)로 저장하며 TTL을 사용하지 않습니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficInFlightDedupeService {

    private static final String FIELD_PROCESSED_INDIVIDUAL_DATA = "processed_individual_data";
    private static final String FIELD_PROCESSED_SHARED_DATA = "processed_shared_data";
    private static final String FIELD_PROCESSED_QOS_DATA = "processed_qos_data";
    private static final String FIELD_RETRY_COUNT = "retry_count";
    private static final String DEFAULT_ZERO = "0";

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    /**
     * traceId의 in-flight 멱등 hash를 생성하거나 기존 값을 조회합니다.
     * - 키가 없으면 processed_individual_data=0, processed_shared_data=0, processed_qos_data=0, retry_count=0으로 생성합니다.
     * - 키가 있으면 기존 필드를 정규화해 반환합니다.
     */
    public TrafficInFlightIdempotencyEntryResult createOrGet(String traceId) {
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);

        long createdResult = trafficLuaScriptInfraService.executeInFlightCreateIfAbsent(
                dedupeKey,
                FIELD_PROCESSED_INDIVIDUAL_DATA,
                FIELD_PROCESSED_SHARED_DATA,
                FIELD_PROCESSED_QOS_DATA,
                FIELD_RETRY_COUNT,
                DEFAULT_ZERO
        );
        boolean created = createdResult == 1L;
        if (created) {
            return new TrafficInFlightIdempotencyEntryResult(
                    true,
                    TrafficInFlightIdempotencyEntry.of(dedupeKey, 0L, 0L, 0L, 0)
            );
        }

        long processedIndividualData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_INDIVIDUAL_DATA),
                FIELD_PROCESSED_INDIVIDUAL_DATA
        );
        long processedSharedData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_SHARED_DATA),
                FIELD_PROCESSED_SHARED_DATA
        );
        long processedQosData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_QOS_DATA),
                FIELD_PROCESSED_QOS_DATA
        );
        int retryCount = parseNonNegativeInt(hashOps().get(dedupeKey, FIELD_RETRY_COUNT));

        return new TrafficInFlightIdempotencyEntryResult(
                false,
                TrafficInFlightIdempotencyEntry.of(
                        dedupeKey,
                        processedIndividualData,
                        processedSharedData,
                        processedQosData,
                        retryCount
                )
        );
    }

    /**
     * traceId의 in-flight 멱등 hash 필드를 조회합니다.
     */
    public Optional<TrafficInFlightIdempotencyEntry> get(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }

        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        if (!Boolean.TRUE.equals(cacheStringRedisTemplate.hasKey(dedupeKey))) {
            return Optional.empty();
        }

        long processedIndividualData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_INDIVIDUAL_DATA),
                FIELD_PROCESSED_INDIVIDUAL_DATA
        );
        long processedSharedData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_SHARED_DATA),
                FIELD_PROCESSED_SHARED_DATA
        );
        long processedQosData = parseNonNegativeLong(
                hashOps().get(dedupeKey, FIELD_PROCESSED_QOS_DATA),
                FIELD_PROCESSED_QOS_DATA
        );
        int retryCount = parseNonNegativeInt(hashOps().get(dedupeKey, FIELD_RETRY_COUNT));

        return Optional.of(TrafficInFlightIdempotencyEntry.of(
                dedupeKey,
                processedIndividualData,
                processedSharedData,
                processedQosData,
                retryCount
        ));
    }

    /**
     * reclaim 시점 재시도 횟수를 1 증가시키고 증가 후 값을 반환합니다.
     * 키가 없으면 hash를 생성하고 retryCount=1로 시작합니다.
     */
    public int incrementRetryOnReclaim(String traceId) {
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        long incremented = trafficLuaScriptInfraService.executeInFlightIncrementRetryWithInit(
                dedupeKey,
                FIELD_PROCESSED_INDIVIDUAL_DATA,
                FIELD_PROCESSED_SHARED_DATA,
                FIELD_PROCESSED_QOS_DATA,
                FIELD_RETRY_COUNT,
                DEFAULT_ZERO
        );
        long safeValue = Math.max(0L, incremented);
        return toSafeInt(safeValue);
    }

    /**
     * 개인풀 차감량을 processed_individual_data에 원자적으로 합산하고 증가 후 값을 반환합니다.
     */
    public long addProcessedDataAtomically(String traceId, long delta) {
        if (delta < 0L) {
            throw new IllegalArgumentException("delta must be 0 or greater");
        }

        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        long incremented = trafficLuaScriptInfraService.executeInFlightIncrementProcessedWithInit(
                dedupeKey,
                FIELD_PROCESSED_INDIVIDUAL_DATA,
                FIELD_PROCESSED_SHARED_DATA,
                FIELD_PROCESSED_QOS_DATA,
                FIELD_RETRY_COUNT,
                DEFAULT_ZERO,
                FIELD_PROCESSED_INDIVIDUAL_DATA,
                delta
        );
        long safeValue = Math.max(0L, incremented);
        return safeValue;
    }

    /**
     * 처리 완료 또는 종결 경로에서 in-flight 멱등 hash를 삭제합니다.
     */
    public void delete(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        cacheStringRedisTemplate.delete(dedupeKey);
    }

    /**
     * Redis 재시도 진행 상태를 로그로만 남깁니다.
     * Redis 장애 상황에서도 본 실행 + hydrate/refill/fallback 흐름(retry/backoff/fallback)을 절대 방해하지 않기 위함입니다.
     */
    public void markRedisRetry(String traceId, int retryAttempt) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        TrafficInFlightState state = TrafficInFlightState.fromRetryAttempt(retryAttempt);
        if (state == null) {
            return;
        }
        log.info("traffic_dedupe_state_log_only traceId={} state={}", traceId, state.name());
    }

    /**
     * 요청 단위 DB fallback 전환 상태를 로그로만 남깁니다.
     * Redis 상태 저장 실패가 DB fallback 자체를 끊지 않도록 Redis 쓰기를 하지 않습니다.
     */
    public void markDbFallback(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        log.info("traffic_dedupe_state_log_only traceId={} state={}", traceId, TrafficInFlightState.DB_FALLBACK.name());
    }

    private HashOperations<String, Object, Object> hashOps() {
        return cacheStringRedisTemplate.opsForHash();
    }

    private long parseNonNegativeLong(Object rawValue, String fieldName) {
        if (rawValue == null) {
            return 0L;
        }

        try {
            return Math.max(0L, Long.parseLong(String.valueOf(rawValue).trim()));
        } catch (NumberFormatException e) {
            log.warn("traffic_dedupe_hash_field_parse_failed field={} rawValue={}", fieldName, rawValue);
            return 0L;
        }
    }

    private int parseNonNegativeInt(Object rawValue) {
        if (rawValue == null) {
            return 0;
        }

        try {
            long parsed = Long.parseLong(String.valueOf(rawValue).trim());
            return toSafeInt(Math.max(0L, parsed));
        } catch (NumberFormatException e) {
            log.warn("traffic_dedupe_hash_field_parse_failed field={} rawValue={}", FIELD_RETRY_COUNT, rawValue);
            return 0;
        }
    }

    private int toSafeInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }
}
