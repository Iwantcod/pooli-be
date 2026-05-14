package com.pooli.traffic.service.runtime;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis의 트래픽 잔량 hash(`remaining_*_amount:*`)와 단순 사용량 값을 읽고 갱신합니다.
 *
 * <p>이 서비스는 Hydrate 시 필요한 최소 필드(`amount`, 개인풀 `qos`)만 다룹니다.
 * Refill 제거 이후 DB 고갈 플래그(`is_empty`)나 잔량 증가 로직은 이 클래스의 책임이 아닙니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRemainingBalanceCacheService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final ObjectProvider<TrafficLuaScriptInfraService> trafficLuaScriptInfraServiceProvider;

    /**
     * 잔량 hash의 `amount` 필드를 long으로 읽습니다.
     *
     * <p>필드가 없거나 숫자가 아니면 호출자가 지정한 기본값을 반환해 조회 경로를 중단시키지 않습니다.
     */
    public long readAmountOrDefault(String balanceKey, long defaultValue) {
        return readAmount(balanceKey).orElse(defaultValue);
    }

    /**
     * 잔량 hash의 `amount` 필드를 읽고, 필드 누락/파싱 실패를 명시적으로 구분할 수 있게 반환합니다.
     */
    public Optional<Long> readAmount(String balanceKey) {
        Object rawAmount = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
        if (rawAmount == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(String.valueOf(rawAmount)));
        } catch (NumberFormatException e) {
            log.warn("traffic_quota_amount_parse_failed key={} value={}", balanceKey, rawAmount);
            return Optional.empty();
        }
    }

    /**
     * Redis string value를 long으로 읽습니다.
     *
     * <p>월별 공유풀 사용량처럼 hash가 아닌 단순 counter 조회에 사용하며,
     * 값이 없거나 파싱할 수 없으면 호출자가 지정한 기본값을 반환합니다.
     */
    public long readValueOrDefault(String key, long defaultValue) {
        String rawValue = cacheStringRedisTemplate.opsForValue().get(key);
        if (rawValue == null) {
            return defaultValue;
        }

        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException e) {
            log.warn("traffic_quota_value_parse_failed key={} value={}", key, rawValue);
            return defaultValue;
        }
    }

    /**
     * 개인풀 월별 잔량 snapshot(`amount`, `qos`)을 한 Redis Lua 원자 구간에서 적재합니다.
     */
    public void hydrateIndividualSnapshot(String balanceKey, long amount, long qos, long expireAtEpochSeconds) {
        long normalizedQos = Math.max(0L, qos);
        long result = requireTrafficLuaScriptInfraService().executeHydrateIndividualSnapshot(
                balanceKey,
                amount,
                normalizedQos,
                expireAtEpochSeconds
        );
        assertSnapshotHydrated(balanceKey, result);
    }

    /**
     * 공유풀 월별 잔량 snapshot(`amount`)을 한 Redis Lua 원자 구간에서 적재합니다.
     */
    public void hydrateSharedSnapshot(String balanceKey, long amount, long expireAtEpochSeconds) {
        long result = requireTrafficLuaScriptInfraService().executeHydrateSharedSnapshot(
                balanceKey,
                amount,
                expireAtEpochSeconds
        );
        assertSnapshotHydrated(balanceKey, result);
    }

    /**
     * snapshot hydrate Lua 실행기가 활성 profile에서 주입되어 있는지 확인합니다.
     */
    private TrafficLuaScriptInfraService requireTrafficLuaScriptInfraService() {
        TrafficLuaScriptInfraService trafficLuaScriptInfraService =
                trafficLuaScriptInfraServiceProvider.getIfAvailable();
        if (trafficLuaScriptInfraService == null) {
            throw new IllegalStateException("Traffic Lua script infra service is not available.");
        }
        return trafficLuaScriptInfraService;
    }

    /**
     * hydrate Lua가 기대한 성공 코드 `1`을 반환했는지 검증하고, 실패 시 호출자에게 즉시 알립니다.
     */
    private void assertSnapshotHydrated(String balanceKey, long result) {
        if (result != 1L) {
            throw new IllegalStateException("Failed to hydrate traffic balance snapshot. key=" + balanceKey);
        }
    }
}
