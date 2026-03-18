package com.pooli.traffic.service.decision;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficFamilyMetaSnapshot;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.payload.SharedPoolThresholdOutboxPayload;
import com.pooli.traffic.mapper.TrafficSharedThresholdAlarmLogMapper;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficFamilyMetaCacheService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유풀 잔량 임계치 도달 여부를 판정하고 Outbox 알람 이벤트를 생성합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficSharedPoolThresholdAlarmService {

    private static final int THRESHOLD_50 = 50;
    private static final int THRESHOLD_30 = 30;
    private static final int THRESHOLD_10 = 10;

    private final TrafficFamilyMetaCacheService trafficFamilyMetaCacheService;
    private final TrafficQuotaCacheService trafficQuotaCacheService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficSharedThresholdAlarmLogMapper trafficSharedThresholdAlarmLogMapper;
    private final RedisOutboxRecordService redisOutboxRecordService;

    /**
     * 공유풀 차감 이후 임계치 도달 여부를 판단하고 월 1회 Outbox 이벤트를 생성합니다.
     */
    public void checkAndEnqueueIfReached(Long familyId) {
        if (familyId == null || familyId <= 0) {
            return;
        }

        TrafficFamilyMetaSnapshot familyMeta = trafficFamilyMetaCacheService.getOrLoad(familyId);
        if (familyMeta == null) {
            return;
        }

        long poolTotalData = normalizeNonNegative(familyMeta.getPoolTotalData());
        if (poolTotalData <= 0) {
            return;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String sharedRemainingKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
        long redisRemainingData = Math.max(0L, trafficQuotaCacheService.readAmountOrDefault(sharedRemainingKey, 0L));
        long dbRemainingData = normalizeNonNegative(familyMeta.getDbRemainingData());
        long actualRemainingData = safeAdd(dbRemainingData, redisRemainingData);
        int remainingPercent = clampPercent(actualRemainingData, poolTotalData);

        List<Integer> thresholds = resolveThresholds(familyMeta);
        if (thresholds.isEmpty()) {
            return;
        }

        String targetMonthText = targetMonth.toString();
        for (Integer thresholdPct : thresholds) {
            if (thresholdPct == null || thresholdPct < 0) {
                continue;
            }
            if (remainingPercent > thresholdPct) {
                continue;
            }

            int inserted = trafficSharedThresholdAlarmLogMapper.insertIgnore(familyId, targetMonthText, thresholdPct);
            if (inserted <= 0) {
                continue;
            }

            enqueueThresholdOutbox(familyId, thresholdPct, targetMonthText);
        }
    }

    private List<Integer> resolveThresholds(TrafficFamilyMetaSnapshot familyMeta) {
        Set<Integer> deduped = new LinkedHashSet<>();
        deduped.add(THRESHOLD_50);
        deduped.add(THRESHOLD_30);
        deduped.add(THRESHOLD_10);

        if (Boolean.TRUE.equals(familyMeta.getThresholdActive())) {
            long custom = normalizeNonNegative(familyMeta.getFamilyThreshold());
            deduped.add((int) Math.max(0L, Math.min(100L, custom)));
        }

        return new ArrayList<>(deduped);
    }

    private void enqueueThresholdOutbox(Long familyId, Integer thresholdPct, String targetMonth) {
        String uuid = UUID.randomUUID().toString();
        SharedPoolThresholdOutboxPayload payload = SharedPoolThresholdOutboxPayload.builder()
                .uuid(uuid)
                .familyId(familyId)
                .thresholdPct(thresholdPct)
                .targetMonth(targetMonth)
                .createdAtEpochMillis(System.currentTimeMillis())
                .build();

        redisOutboxRecordService.createPending(
                OutboxEventType.SHARED_POOL_THRESHOLD_REACHED,
                payload,
                uuid
        );

        log.info(
                "traffic_shared_threshold_outbox_created familyId={} thresholdPct={} targetMonth={}",
                familyId,
                thresholdPct,
                targetMonth
        );
    }

    private int clampPercent(long remaining, long total) {
        if (total <= 0) {
            return 0;
        }
        if (remaining <= 0) {
            return 0;
        }

        long scaled;
        if (remaining > Long.MAX_VALUE / 100L) {
            scaled = Long.MAX_VALUE;
        } else {
            scaled = remaining * 100L;
        }

        long ratio = scaled / total;
        if (ratio <= 0) {
            return 0;
        }
        if (ratio >= 100L) {
            return 100;
        }
        return (int) ratio;
    }

    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    private long safeAdd(long left, long right) {
        if (left <= 0) {
            return Math.max(0L, right);
        }
        if (right <= 0) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
