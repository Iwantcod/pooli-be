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
 * Redis의 트래픽 잔량 hash(`remaining_*_amount:*`)와 숫자형 Redis field/value를 읽고 갱신합니다.
 *
 * <p>이 서비스는 Hydrate 시 필요한 최소 필드(`amount`, 개인풀 `qos`)만 다룹니다.
 * 공유풀 기여처럼 이미 hydrate된 잔량 snapshot 보정이 필요한 경우에만 `amount` field를 조건부 증감합니다.
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
        // Optional 조회 결과가 비어 있으면 호출자가 정한 fallback 값을 그대로 사용합니다.
        return readAmount(balanceKey).orElse(defaultValue);
    }

    /**
     * Redis hash 숫자 필드를 long으로 읽고, 누락/파싱 실패 시 기본값을 반환합니다.
     */
    public long readHashFieldOrDefault(String key, String field, long defaultValue) {
        // 지정한 hash field만 읽어 범용 hash counter 조회에 사용합니다.
        Object rawValue = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (rawValue == null) {
            return defaultValue;
        }

        try {
            // RedisTemplate은 String 값을 Object로 돌려주므로 문자열 변환 후 long으로 파싱합니다.
            return Long.parseLong(String.valueOf(rawValue));
        } catch (NumberFormatException e) {
            log.warn("traffic_quota_hash_field_parse_failed key={} field={} value={}", key, field, rawValue);
            return defaultValue;
        }
    }

    /**
     * 잔량 hash의 `amount` 필드를 읽고, 필드 누락/파싱 실패를 명시적으로 구분할 수 있게 반환합니다.
     */
    public Optional<Long> readAmount(String balanceKey) {
        // 잔량 hash 계약에서 실제 잔량은 amount field에만 저장됩니다.
        Object rawAmount = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
        if (rawAmount == null) {
            return Optional.empty();
        }

        try {
            // 호출자가 필드 누락과 0 값을 구분할 수 있도록 Optional로 반환합니다.
            return Optional.of(Long.parseLong(String.valueOf(rawAmount)));
        } catch (NumberFormatException e) {
            log.warn("traffic_quota_amount_parse_failed key={} value={}", balanceKey, rawAmount);
            return Optional.empty();
        }
    }

    /**
     * Redis hash field가 존재하는지 확인합니다.
     *
     * <p>값이 0이어도 준비된 field일 수 있으므로, 숫자 파싱 대신 Redis field 존재 여부만 확인합니다.</p>
     */
    public boolean hasHashField(String key, String field) {
        // hash field 존재 여부만 확인해 0, -1 같은 정상 sentinel 값을 누락으로 오인하지 않습니다.
        Boolean hasField = cacheStringRedisTemplate.opsForHash().hasKey(key, field);
        return Boolean.TRUE.equals(hasField);
    }

    /**
     * Redis key가 존재하는지 확인합니다.
     *
     * <p>공유풀 preflight처럼 hash field 준비 여부가 아니라 key 자체의 존재 여부만 필요한 흐름에서 사용합니다.</p>
     */
    public boolean hasKey(String key) {
        // 값의 형태나 field 구성은 확인하지 않고 Redis key 존재 여부만 판단합니다.
        Boolean hasKey = cacheStringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * 잔량 hash가 이미 hydrate되어 있을 때만 `amount` 필드를 증감합니다.
     *
     * <p>key 또는 amount가 없으면 RDB source만 갱신된 상태로 두고, 다음 조회 hydrate가 반영하게 합니다.
     */
    public boolean incrementAmountIfPresent(String balanceKey, long delta) {
        // 먼저 amount 존재 여부를 확인해 hydrate되지 않은 key를 새로 만들지 않습니다.
        Optional<Long> currentAmount = readAmount(balanceKey);
        if (currentAmount.isEmpty()) {
            return false;
        }

        // 무제한 sentinel(-1)은 차감 요청으로도 값이 내려가지 않도록 유지합니다.
        if (currentAmount.get() < 0L && delta < 0L) {
            return true;
        }

        // amount가 있는 정상 snapshot에만 Redis hash increment를 적용합니다.
        cacheStringRedisTemplate.opsForHash().increment(balanceKey, "amount", delta);
        return true;
    }

    /**
     * Redis string value를 long으로 읽습니다.
     *
     * <p>hash가 아닌 숫자형 string key 조회에 사용하며, 값이 없거나 파싱할 수 없으면 호출자가 지정한 기본값을 반환합니다.
     */
    public long readValueOrDefault(String key, long defaultValue) {
        // hash가 아닌 string counter/value 키를 직접 조회합니다.
        String rawValue = cacheStringRedisTemplate.opsForValue().get(key);
        if (rawValue == null) {
            return defaultValue;
        }

        try {
            // 파싱 가능한 값만 화면/계산 보정값으로 사용합니다.
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
        // QoS 속도는 음수가 의미 없으므로 snapshot 적재 전에 0 이상으로 보정합니다.
        long normalizedQos = Math.max(0L, qos);
        // DEL + HSET + EXPIREAT가 한 원자 구간에서 실행되도록 Lua에 위임합니다.
        long result = requireTrafficLuaScriptInfraService().executeHydrateIndividualSnapshot(
                balanceKey,
                amount,
                normalizedQos,
                expireAtEpochSeconds
        );
        // Lua 실행 결과가 기대한 성공 코드인지 확인해 실패를 조용히 넘기지 않습니다.
        assertSnapshotHydrated(balanceKey, result);
    }

    /**
     * 공유풀 월별 잔량 snapshot(`amount`)을 한 Redis Lua 원자 구간에서 적재합니다.
     */
    public void hydrateSharedSnapshot(String balanceKey, long amount, long expireAtEpochSeconds) {
        // 공유풀 snapshot은 amount만 필요하므로 해당 값과 만료 시각만 Lua에 전달합니다.
        long result = requireTrafficLuaScriptInfraService().executeHydrateSharedSnapshot(
                balanceKey,
                amount,
                expireAtEpochSeconds
        );
        // Lua 실행 결과가 기대한 성공 코드인지 확인해 실패를 조용히 넘기지 않습니다.
        assertSnapshotHydrated(balanceKey, result);
    }

    /**
     * snapshot hydrate Lua 실행기가 활성 profile에서 주입되어 있는지 확인합니다.
     */
    private TrafficLuaScriptInfraService requireTrafficLuaScriptInfraService() {
        // profile 조건 때문에 bean이 없을 수 있으므로 ObjectProvider에서 지연 조회합니다.
        TrafficLuaScriptInfraService trafficLuaScriptInfraService =
                trafficLuaScriptInfraServiceProvider.getIfAvailable();
        if (trafficLuaScriptInfraService == null) {
            throw new IllegalStateException("Traffic Lua script infra service is not available.");
        }
        // 이후 hydrate 메서드는 null 검사를 반복하지 않고 반환된 실행기를 사용합니다.
        return trafficLuaScriptInfraService;
    }

    /**
     * hydrate Lua가 기대한 성공 코드 `1`을 반환했는지 검증하고, 실패 시 호출자에게 즉시 알립니다.
     */
    private void assertSnapshotHydrated(String balanceKey, long result) {
        // hydrate Lua는 성공 시 1을 반환하므로 그 외 값은 snapshot 적재 실패로 간주합니다.
        if (result != 1L) {
            throw new IllegalStateException("Failed to hydrate traffic balance snapshot. key=" + balanceKey);
        }
    }
}
