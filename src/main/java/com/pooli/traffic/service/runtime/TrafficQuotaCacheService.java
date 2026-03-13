package com.pooli.traffic.service.runtime;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 트래픽 잔량 해시(remaining_*)를 읽고 갱신하는 Redis 유틸 서비스입니다.
 * HYDRATE/REFILL 어댑터가 공통적으로 사용하는 캐시 접근 로직을 모읍니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficQuotaCacheService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * 외부 저장소에서 현재 데이터를 조회해 반환합니다.
     */
    public long readAmountOrDefault(String balanceKey, long defaultValue) {
        // amount 필드가 없으면 defaultValue를 반환해 호출자가 분기 판단을 이어갈 수 있게 한다.
        Object rawAmount = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
        if (rawAmount == null) {
            return defaultValue;
        }

        try {
            return Long.parseLong(String.valueOf(rawAmount));
        } catch (NumberFormatException e) {
            // 캐시 값이 깨진 경우에도 어댑터 흐름이 멈추지 않게 defaultValue로 보정한다.
            log.warn("traffic_quota_amount_parse_failed key={} value={}", balanceKey, rawAmount);
            return defaultValue;
        }
    }

    /**
      * `hydrateBalance` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    public void hydrateBalance(String balanceKey, long initialAmount, long expireAtEpochSeconds) {
        long normalizedAmount = Math.max(0L, initialAmount);

        // HYDRATE 단계는 키가 없던 상태를 복구하는 목적이므로 putIfAbsent를 사용한다.
        cacheStringRedisTemplate.opsForHash().putIfAbsent(balanceKey, "amount", String.valueOf(normalizedAmount));
        cacheStringRedisTemplate.opsForHash().putIfAbsent(balanceKey, "is_empty", normalizedAmount <= 0 ? "1" : "0");

        // 월별 잔량 키는 명세에 맞춰 월말+10d 시점으로 만료를 설정한다.
        cacheStringRedisTemplate.expireAt(balanceKey, Instant.ofEpochSecond(expireAtEpochSeconds));
    }

    /**
     * 개인풀 잔량 해시에 QoS 값을 기록합니다.
     * null/음수는 허용하지 않으므로 0 이상으로 정규화해 저장합니다.
     */
    public void putQos(String balanceKey, long qos) {
        long normalizedQos = Math.max(0L, qos);
        cacheStringRedisTemplate.opsForHash().put(balanceKey, "qos", String.valueOf(normalizedQos));
    }

    /**
      * `refillBalance` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    public void refillBalance(String balanceKey, long refillAmount, long expireAtEpochSeconds) {
        long normalizedRefillAmount = Math.max(0L, refillAmount);
        if (normalizedRefillAmount <= 0) {
            // 0 이하 리필은 실효성이 없으므로 무해하게 종료한다.
            return;
        }

        // 리필은 기존 잔량에 누적 증가시키는 동작이므로 increment를 사용한다.
        cacheStringRedisTemplate.opsForHash().increment(balanceKey, "amount", normalizedRefillAmount);
        cacheStringRedisTemplate.opsForHash().put(balanceKey, "is_empty", "0");

        // 리필 시점에도 동일 TTL 정책을 유지한다.
        cacheStringRedisTemplate.expireAt(balanceKey, Instant.ofEpochSecond(expireAtEpochSeconds));
    }
}
