package com.pooli.traffic.service.runtime;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.enums.TrafficInFlightState;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * traceId 기준 in-flight dedupe 선점을 담당하는 서비스입니다.
 * 동일 traceId의 중복 처리/동시 처리 경쟁을 Redis NX+TTL로 완화합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficInFlightDedupeService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
      * `tryClaim` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    public boolean tryClaim(String traceId) {
        // traceId에서 dedupe 키를 생성한다. (namespace 포함)
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);

        // SET NX EX(60s)와 동일한 의미:
        // 1) 키가 없을 때만 생성(NX)
        // 2) INFLIGHT_TTL_SEC 이후 자동 만료
        Boolean claimed = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                dedupeKey,
                TrafficInFlightState.CLAIMED.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );

        boolean acquired = Boolean.TRUE.equals(claimed);
        if (!acquired) {
            log.info("traffic_dedupe_claim_skipped key={}", dedupeKey);
        }
        return acquired;
    }

    /**
     * traceId의 dedupe 상태를 조회합니다.
     * 값이 없거나 정의되지 않은 상태 문자열이면 빈 값을 반환해 호출 측이 안전하게 분기하도록 돕습니다.
     */
    public Optional<TrafficInFlightState> findState(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }

        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        String stateValue = cacheStringRedisTemplate.opsForValue().get(dedupeKey);
        if (stateValue == null || stateValue.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(TrafficInFlightState.valueOf(stateValue.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            // 운영 중 예상치 못한 값이 들어와도 처리 흐름을 끊지 않기 위해 empty로 완충한다.
            log.warn("traffic_dedupe_state_unknown key={} rawState={}", dedupeKey, stateValue);
            return Optional.empty();
        }
    }

    /**
      * `release` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    public void release(String traceId) {
        // DONE 상태를 잠시 유지해 후속 재전달에서 현재 처리 결과를 추적할 수 있게 한다.
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(traceId);
        cacheStringRedisTemplate.opsForValue().set(
                dedupeKey,
                TrafficInFlightState.DONE.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
    }

    /**
     * Redis 재시도 진행 상태를 로그로만 남깁니다.
     * Redis 장애 상황에서도 본 복구 흐름(retry/backoff/fallback)을 절대 방해하지 않기 위함입니다.
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
}
