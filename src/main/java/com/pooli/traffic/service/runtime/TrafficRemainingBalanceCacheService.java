package com.pooli.traffic.service.runtime;

import java.time.Instant;

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

    /**
     * 잔량 hash의 `amount` 필드를 long으로 읽습니다.
     *
     * <p>필드가 없거나 숫자가 아니면 호출자가 지정한 기본값을 반환해 조회 경로를 중단시키지 않습니다.
     */
    public long readAmountOrDefault(String balanceKey, long defaultValue) {
        Object rawAmount = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
        if (rawAmount == null) {
            return defaultValue;
        }

        try {
            return Long.parseLong(String.valueOf(rawAmount));
        } catch (NumberFormatException e) {
            log.warn("traffic_quota_amount_parse_failed key={} value={}", balanceKey, rawAmount);
            return defaultValue;
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
     * 월별 잔량 hash를 DB 원천값 기준으로 생성합니다.
     *
     * <p>Hydrate는 누락 key 복구 목적이므로 기존 `amount`가 있으면 덮어쓰지 않습니다.
     * `amount=-1`은 무제한 sentinel이므로 보정하지 않고 그대로 저장합니다.
     * TTL은 월말+10일 정책으로 계산된 epoch second를 그대로 적용합니다.
     */
    public void hydrateBalance(String balanceKey, long amount, long expireAtEpochSeconds) {
        cacheStringRedisTemplate.opsForHash().putIfAbsent(balanceKey, "amount", String.valueOf(amount));
        cacheStringRedisTemplate.expireAt(balanceKey, Instant.ofEpochSecond(expireAtEpochSeconds));
    }

    /**
     * 개인풀 잔량 hash의 `qos` 필드를 저장합니다.
     *
     * <p>QoS 값은 잔량 차감 대상이 아니라 통합 Lua가 잔량 부족 시 처리 가능한 한도로 해석합니다.
     */
    public void putQos(String balanceKey, long qos) {
        long normalizedQos = Math.max(0L, qos);
        cacheStringRedisTemplate.opsForHash().put(balanceKey, "qos", String.valueOf(normalizedQos));
    }
}
