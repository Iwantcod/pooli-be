package com.pooli.traffic.service.runtime;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 차감 처리량을 초 단위 Redis 버킷에 기록합니다.
 *
 * <p>현재 버킷은 실시간 관측/속도성 보조 데이터이며 월별 snapshot source 계산에는 사용하지 않습니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficRecentUsageBucketService {

    private static final long SPEED_BUCKET_TTL_SECONDS = 15L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
     * 현재 초의 개인/공유풀 사용량 버킷을 증가시키고 짧은 TTL을 갱신합니다.
     *
     * <p>필수 식별자나 사용량이 없으면 기록하지 않습니다. 기록 실패는 핵심 차감 결과와 분리해 로그만 남깁니다.
     */
    public void recordUsage(TrafficPoolType poolType, TrafficPayloadReqDto payload, long usedBytes) {
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
                    "traffic_speed_bucket_record_failed poolType={} ownerId={} usedBytes={}",
                    poolType,
                    ownerId,
                    usedBytes,
                    e
            );
        }
    }

    /**
     * 풀 유형에 맞는 버킷 owner를 선택합니다.
     *
     * <p>개인풀은 lineId, 공유풀은 familyId를 owner로 사용합니다.
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
     * 풀 유형, owner, epoch second를 Redis 버킷 key로 변환합니다.
     */
    private String resolveBucketKey(TrafficPoolType poolType, long ownerId, long epochSecond) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualKey(ownerId, epochSecond);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedKey(ownerId, epochSecond);
        };
    }
}
