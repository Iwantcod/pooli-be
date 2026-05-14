package com.pooli.traffic.service.runtime;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficFamilyMetaSnapshot;
import com.pooli.traffic.mapper.TrafficFamilyMetaMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FAMILY 메타(총량/임계치)를 Redis에 캐시하고 write-through를 적용합니다.
 *
 * <p>실시간 공유풀 잔량은 family meta cache가 아니라 Redis balance snapshot의 amount 필드에서 조회합니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficFamilyMetaCacheService {

    private static final String FIELD_POOL_TOTAL_DATA = "pool_total_data";
    private static final String FIELD_FAMILY_THRESHOLD = "family_threshold";
    private static final String FIELD_THRESHOLD_ACTIVE = "is_threshold_active";

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficFamilyMetaMapper trafficFamilyMetaMapper;

    /**
     * 캐시를 우선 조회하고, 미스면 DB에서 hydrate 후 반환합니다.
     */
    public TrafficFamilyMetaSnapshot getOrLoad(long familyId) {
        if (familyId <= 0) {
            return null;
        }

        TrafficFamilyMetaSnapshot cached = readFromCache(familyId);
        if (cached != null) {
            return cached;
        }

        return loadFromDbAndCache(familyId);
    }

    /**
     * 공유풀 기여(write-through): 메타 캐시의 총량만 증가시킵니다.
     */
    public void increasePoolTotal(long familyId, long amount) {
        long normalizedAmount = Math.max(0L, amount);
        if (familyId <= 0 || normalizedAmount <= 0) {
            return;
        }

        try {
            String key = trafficRedisKeyFactory.familyMetaKey(familyId);
            if (readFromCache(familyId) == null) {
                // 캐시 미스 시점에는 DB에 최신 값이 반영된 상태이므로 hydrate만 수행하고 delta를 재적용하지 않습니다.
                loadFromDbAndCache(familyId);
                return;
            }
            cacheStringRedisTemplate.opsForHash().increment(key, FIELD_POOL_TOTAL_DATA, normalizedAmount);
        } catch (RuntimeException e) {
            log.warn(
                    "traffic_family_meta_cache_increase_pool_total_failed familyId={} amount={}",
                    familyId,
                    normalizedAmount,
                    e
            );
        }
    }

    /**
     * 임계치 설정(write-through): 커스텀 임계치와 활성화 여부를 캐시에 반영합니다.
     */
    public void updateThreshold(long familyId, long familyThreshold, boolean thresholdActive) {
        if (familyId <= 0) {
            return;
        }

        try {
            ensureCached(familyId);
            String key = trafficRedisKeyFactory.familyMetaKey(familyId);
            cacheStringRedisTemplate.opsForHash().put(key, FIELD_FAMILY_THRESHOLD, String.valueOf(Math.max(0L, familyThreshold)));
            cacheStringRedisTemplate.opsForHash().put(key, FIELD_THRESHOLD_ACTIVE, thresholdActive ? "1" : "0");
        } catch (RuntimeException e) {
            log.warn(
                    "traffic_family_meta_cache_update_threshold_failed familyId={} threshold={} active={}",
                    familyId,
                    familyThreshold,
                    thresholdActive,
                    e
            );
        }
    }

    private void ensureCached(long familyId) {
        if (readFromCache(familyId) != null) {
            return;
        }
        loadFromDbAndCache(familyId);
    }

    private TrafficFamilyMetaSnapshot readFromCache(long familyId) {
        String key = trafficRedisKeyFactory.familyMetaKey(familyId);
        Map<Object, Object> rawEntries = cacheStringRedisTemplate.opsForHash().entries(key);
        if (rawEntries == null || rawEntries.isEmpty()) {
            return null;
        }

        Long poolTotalData = parseLong(rawEntries.get(FIELD_POOL_TOTAL_DATA));
        Long familyThreshold = parseLong(rawEntries.get(FIELD_FAMILY_THRESHOLD));
        Boolean thresholdActive = parseBoolean(rawEntries.get(FIELD_THRESHOLD_ACTIVE));

        if (poolTotalData == null || familyThreshold == null || thresholdActive == null) {
            return null;
        }

        return TrafficFamilyMetaSnapshot.builder()
                .familyId(familyId)
                .poolTotalData(Math.max(0L, poolTotalData))
                .familyThreshold(Math.max(0L, familyThreshold))
                .thresholdActive(thresholdActive)
                .build();
    }

    private TrafficFamilyMetaSnapshot loadFromDbAndCache(long familyId) {
        TrafficFamilyMetaSnapshot fromDb = trafficFamilyMetaMapper.selectFamilyMeta(familyId);
        if (fromDb == null) {
            return null;
        }

        long poolTotalData = Math.max(0L, normalizeNonNegative(fromDb.getPoolTotalData()));
        long familyThreshold = Math.max(0L, normalizeNonNegative(fromDb.getFamilyThreshold()));
        boolean thresholdActive = Boolean.TRUE.equals(fromDb.getThresholdActive());

        String key = trafficRedisKeyFactory.familyMetaKey(familyId);
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_POOL_TOTAL_DATA, String.valueOf(poolTotalData));
        values.put(FIELD_FAMILY_THRESHOLD, String.valueOf(familyThreshold));
        values.put(FIELD_THRESHOLD_ACTIVE, thresholdActive ? "1" : "0");
        cacheStringRedisTemplate.opsForHash().putAll(key, values);

        return TrafficFamilyMetaSnapshot.builder()
                .familyId(familyId)
                .poolTotalData(poolTotalData)
                .familyThreshold(familyThreshold)
                .thresholdActive(thresholdActive)
                .build();
    }

    private Long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) {
            return null;
        }

        String normalized = String.valueOf(value).trim();
        if ("1".equals(normalized) || "true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
    }
}
