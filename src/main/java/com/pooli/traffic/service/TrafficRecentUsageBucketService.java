package com.pooli.traffic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 최근 차감량 버킷을 Redis에 기록하고 리필 계산값(delta/unit/threshold)을 제공합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficRecentUsageBucketService {

    private static final long RECENT_WINDOW_SECONDS = 10L;
    private static final long SPEED_BUCKET_TTL_SECONDS = 15L;
    private static final long REFILL_UNIT_MULTIPLIER = 10L;
    private static final long THRESHOLD_NUMERATOR = 3L;
    private static final long THRESHOLD_DENOMINATOR = 10L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
     * 현재 tick의 실제 차감량을 "초 단위 속도 버킷"에 기록합니다.
     *
     * <p>동작 규칙:
     * 1) `usedBytes > 0`인 경우에만 기록합니다.
     * 2) 풀 유형에 따라 owner(lineId/familyId)를 선택합니다.
     * 3) 같은 초(epochSec)로 들어오는 값은 `INCRBY`로 누적합니다.
     * 4) 버킷 키 TTL은 기록 시점마다 15초로 갱신합니다.
     *
     * <p>기록 실패는 리필 계산 정확도에만 영향을 주므로, 전체 차감 흐름을 중단하지 않게
     * WARN 로그만 남기고 예외를 삼킵니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param payload 요청 컨텍스트(traceId, lineId, familyId 포함)
     * @param usedBytes 현재 tick 실제 차감량(Byte)
     */
    public void recordTickUsage(TrafficPoolType poolType, TrafficPayloadReqDto payload, long usedBytes) {
        if (poolType == null || payload == null || usedBytes <= 0) {
            return;
        }

        Long ownerId = resolveOwnerId(poolType, payload);
        if (ownerId == null || ownerId <= 0) {
            return;
        }

        String bucketKey = resolveBucketKey(poolType, ownerId, Instant.now().getEpochSecond());
        if (bucketKey == null || bucketKey.isBlank()) {
            return;
        }

        try {
            Long updatedValue = cacheStringRedisTemplate.opsForValue().increment(bucketKey, usedBytes);
            if (updatedValue != null) {
                cacheStringRedisTemplate.expire(bucketKey, Duration.ofSeconds(SPEED_BUCKET_TTL_SECONDS));
            }
        } catch (Exception e) {
            log.warn(
                    "traffic_speed_bucket_record_failed traceId={} poolType={} ownerId={} usedBytes={}",
                    payload.getTraceId(),
                    poolType,
                    ownerId,
                    usedBytes,
                    e
            );
        }
    }

    /**
     * 최신 버킷 데이터를 기준으로 리필 계획(delta/unit/threshold)을 계산합니다.
     *
     * <p>계산 우선순위:
     * 1) 최근 10초 버킷 집계(RECENT_10S)
     * 2) 최근 10초가 비면 TTL 내 전체 버킷 집계(ALL_BUCKETS)
     * 3) 둘 다 비면 apiTotalData fallback(API_TOTAL_DATA)
     *
     * <p>산식:
     * - delta = ceil(sum / bucketCount)
     * - refillUnit = delta * 10
     * - threshold = ceil(refillUnit * 3 / 10), 최소 1 보정
     *
     * @param poolType 개인/공유 풀 구분
     * @param payload 요청 컨텍스트(apiTotalData 포함)
     * @return 리필 판단에 필요한 계산 결과
     */
    public TrafficRefillPlan resolveRefillPlan(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        Long ownerId = resolveOwnerId(poolType, payload);
        if (poolType == null || ownerId == null || ownerId <= 0) {
            return buildFallbackPlan(apiTotalData);
        }

        BucketAggregate recentAggregate = aggregateRecentWindow(poolType, ownerId);
        if (recentAggregate.bucketCount > 0) {
            long delta = divideCeil(recentAggregate.bucketSum, recentAggregate.bucketCount);
            long refillUnit = safeMultiply(delta, REFILL_UNIT_MULTIPLIER);
            long threshold = divideCeil(
                    safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                    THRESHOLD_DENOMINATOR
            );
            return TrafficRefillPlan.builder()
                    .delta(delta)
                    .bucketCount((int) recentAggregate.bucketCount)
                    .bucketSum(recentAggregate.bucketSum)
                    .refillUnit(refillUnit)
                    .threshold(Math.max(1L, threshold))
                    .source("RECENT_10S")
                    .build();
        }

        BucketAggregate allAggregate = aggregateAllBuckets(poolType, ownerId);
        if (allAggregate.bucketCount > 0) {
            long delta = divideCeil(allAggregate.bucketSum, allAggregate.bucketCount);
            long refillUnit = safeMultiply(delta, REFILL_UNIT_MULTIPLIER);
            long threshold = divideCeil(
                    safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                    THRESHOLD_DENOMINATOR
            );
            return TrafficRefillPlan.builder()
                    .delta(delta)
                    .bucketCount((int) allAggregate.bucketCount)
                    .bucketSum(allAggregate.bucketSum)
                    .refillUnit(refillUnit)
                    .threshold(Math.max(1L, threshold))
                    .source("ALL_BUCKETS")
                    .build();
        }

        return buildFallbackPlan(apiTotalData);
    }

    /**
     * 버킷 데이터가 없을 때 적용하는 fallback 리필 계획을 생성합니다.
     *
     * <p>fallback 규칙:
     * - refillUnit = max(apiTotalData, 0)
     * - threshold = ceil(refillUnit * 3 / 10), 최소 1 보정
     *
     * @param apiTotalData 요청 총량(Byte)
     * @return source=API_TOTAL_DATA 인 fallback 리필 계획
     */
    private TrafficRefillPlan buildFallbackPlan(long apiTotalData) {
        long refillUnit = Math.max(0L, apiTotalData);
        long threshold = divideCeil(
                safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                THRESHOLD_DENOMINATOR
        );
        return TrafficRefillPlan.builder()
                .delta(refillUnit)
                .bucketCount(0)
                .bucketSum(0L)
                .refillUnit(refillUnit)
                .threshold(Math.max(1L, threshold))
                .source("API_TOTAL_DATA")
                .build();
    }

    /**
     * 현재 시각 기준 최근 10초 버킷 키 목록을 만들고 합계/개수를 집계합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param ownerId lineId 또는 familyId
     * @return 집계 결과(sum, bucketCount)
     */
    private BucketAggregate aggregateRecentWindow(TrafficPoolType poolType, long ownerId) {
        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = new ArrayList<>();
        for (long i = 0; i < RECENT_WINDOW_SECONDS; i++) {
            keys.add(resolveBucketKey(poolType, ownerId, nowSec - i));
        }
        return aggregateKeys(keys);
    }

    /**
     * TTL 내 남아 있는 전체 버킷을 패턴 조회해 합계/개수를 집계합니다.
     *
     * <p>최근 10초 버킷이 비었을 때의 2차 fallback 집계로 사용합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param ownerId lineId 또는 familyId
     * @return 집계 결과(sum, bucketCount)
     */
    private BucketAggregate aggregateAllBuckets(TrafficPoolType poolType, long ownerId) {
        String pattern = resolveBucketPattern(poolType, ownerId);
        if (pattern == null || pattern.isBlank()) {
            return BucketAggregate.empty();
        }

        Set<String> keys = cacheStringRedisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return BucketAggregate.empty();
        }
        return aggregateKeys(new ArrayList<>(keys));
    }

    /**
     * 전달받은 버킷 키 목록에서 값을 읽어 합계/개수를 계산합니다.
     *
     * <p>`multiGet` 결과 중 양수 값만 유효 버킷으로 간주합니다.
     * 값이 없거나 파싱 실패, 0/음수 값은 집계에서 제외합니다.
     *
     * @param keys 버킷 키 목록
     * @return 집계 결과(sum, bucketCount)
     */
    private BucketAggregate aggregateKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return BucketAggregate.empty();
        }

        List<String> values = cacheStringRedisTemplate.opsForValue().multiGet(keys);
        if (values == null || values.isEmpty()) {
            return BucketAggregate.empty();
        }

        long sum = 0L;
        long count = 0L;
        for (String value : values) {
            long parsedValue = parsePositiveLong(value);
            if (parsedValue <= 0) {
                continue;
            }
            sum += parsedValue;
            count++;
        }

        if (count <= 0) {
            return BucketAggregate.empty();
        }
        return new BucketAggregate(sum, count);
    }

    /**
     * 풀 유형에 맞는 버킷 owner 식별자를 반환합니다.
     *
     * <p>INDIVIDUAL -> lineId, SHARED -> familyId
     *
     * @param poolType 개인/공유 풀 구분
     * @param payload 요청 컨텍스트
     * @return ownerId(lineId/familyId), 없으면 null
     */
    private Long resolveOwnerId(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (poolType == null || payload == null) {
            return null;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId();
            case SHARED -> payload.getFamilyId();
        };
    }

    /**
     * 풀 유형에 맞는 "단일 초 버킷 키"를 생성합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param ownerId lineId 또는 familyId
     * @param epochSecond 대상 초
     * @return 버킷 키 문자열
     */
    private String resolveBucketKey(TrafficPoolType poolType, long ownerId, long epochSecond) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualKey(ownerId, epochSecond);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedKey(ownerId, epochSecond);
        };
    }

    /**
     * 풀 유형에 맞는 버킷 검색 패턴(`...:*`)을 생성합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param ownerId lineId 또는 familyId
     * @return 키 패턴 문자열
     */
    private String resolveBucketPattern(TrafficPoolType poolType, long ownerId) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualPattern(ownerId);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedPattern(ownerId);
        };
    }

    /**
     * 양수 정수 나눗셈 결과를 올림(ceil)으로 계산합니다.
     *
     * <p>분모/분자가 0 이하인 경우 0을 반환해 후속 계산을 안전하게 유지합니다.
     */
    private long divideCeil(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) {
            return 0L;
        }
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        if (remainder == 0) {
            return quotient;
        }
        return quotient + 1L;
    }

    /**
     * Long 오버플로우를 방어하며 곱셈을 수행합니다.
     *
     * <p>곱셈 범위를 초과하면 Long.MAX_VALUE로 포화시켜 계산 예외를 방지합니다.
     */
    private long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    /**
     * NULL/음수 값을 0으로 보정해 non-negative 값으로 정규화합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * Redis 문자열 값을 양수 long으로 파싱합니다.
     *
     * <p>빈 값, 파싱 실패, 0/음수는 모두 0으로 반환합니다.
     */
    private long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }

        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 버킷 집계(sum/count)를 함께 전달하기 위한 경량 값 객체입니다.
     */
    private record BucketAggregate(long bucketSum, long bucketCount) {
        /**
         * 유효 버킷이 없을 때 사용하는 빈 집계값을 반환합니다.
         */
        private static BucketAggregate empty() {
            return new BucketAggregate(0L, 0L);
        }
    }
}
