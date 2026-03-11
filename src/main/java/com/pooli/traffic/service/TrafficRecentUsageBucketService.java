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

    private BucketAggregate aggregateRecentWindow(TrafficPoolType poolType, long ownerId) {
        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = new ArrayList<>();
        for (long i = 0; i < RECENT_WINDOW_SECONDS; i++) {
            keys.add(resolveBucketKey(poolType, ownerId, nowSec - i));
        }
        return aggregateKeys(keys);
    }

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

    private Long resolveOwnerId(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (poolType == null || payload == null) {
            return null;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId();
            case SHARED -> payload.getFamilyId();
        };
    }

    private String resolveBucketKey(TrafficPoolType poolType, long ownerId, long epochSecond) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualKey(ownerId, epochSecond);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedKey(ownerId, epochSecond);
        };
    }

    private String resolveBucketPattern(TrafficPoolType poolType, long ownerId) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualPattern(ownerId);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedPattern(ownerId);
        };
    }

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

    private long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

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

    private record BucketAggregate(long bucketSum, long bucketCount) {
        private static BucketAggregate empty() {
            return new BucketAggregate(0L, 0L);
        }
    }
}
