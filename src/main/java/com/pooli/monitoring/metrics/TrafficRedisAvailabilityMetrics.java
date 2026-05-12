package com.pooli.monitoring.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Redis 가용성 alert rule이 판단에 사용할 raw metric을 노출합니다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficRedisAvailabilityMetrics {

    private final MeterRegistry meterRegistry;

    private final EnumMap<RedisTarget, AtomicInteger> pingUpGauges = new EnumMap<>(RedisTarget.class);
    private final EnumMap<RedisTarget, AtomicInteger> consecutivePingFailures = new EnumMap<>(RedisTarget.class);

    /**
     * cache/streams Redis별 PING gauge를 애플리케이션 시작 시 한 번 등록합니다.
     *
     * <p>1. Redis 종류별로 gauge 값 보관용 {@link AtomicInteger}를 생성합니다.
     * <br>2. `traffic_redis_ping_up`은 마지막 PING 성공 여부를 1/0으로 노출합니다.
     * <br>3. `traffic_redis_ping_failures`는 연속 PING 실패 횟수를 노출합니다.
     */
    @PostConstruct
    void init() {
        for (RedisTarget redisTarget : RedisTarget.values()) {
            // Gauge는 값을 직접 보관하지 않으므로, Redis 종류별 상태 holder를 먼저 준비합니다.
            AtomicInteger pingUp = new AtomicInteger(0);
            AtomicInteger failures = new AtomicInteger(0);
            pingUpGauges.put(redisTarget, pingUp);
            consecutivePingFailures.put(redisTarget, failures);

            // 마지막 PING 결과를 1(up) 또는 0(down)으로 Prometheus에 노출합니다.
            Gauge.builder("traffic_redis_ping_up", pingUp, AtomicInteger::get)
                    .description("Redis PING status. 1 means up, 0 means down.")
                    .tag("redis", redisTarget.tagValue())
                    .register(meterRegistry);

            // global-down 보조 조건에 필요한 연속 PING 실패 횟수를 Redis 종류별로 노출합니다.
            Gauge.builder("traffic_redis_ping_failures", failures, AtomicInteger::get)
                    .description("Consecutive Redis PING failure count.")
                    .tag("redis", redisTarget.tagValue())
                    .register(meterRegistry);
        }
    }

    /**
     * Redis 명령 시도 횟수를 증가시킵니다.
     *
     * <p>1. Redis 종류를 Prometheus tag 값으로 정규화합니다.
     * <br>2. `traffic_redis_ops_total{redis=...}` counter를 가져오거나 생성합니다.
     * <br>3. 성공/실패와 무관하게 "시도" 1건으로 증가시킵니다.
     */
    public void incrementOperation(RedisTarget redisTarget) {
        meterRegistry.counter(
                "traffic_redis_ops_total",
                "redis", normalize(redisTarget)
        ).increment();
    }

    /**
     * Redis 명령 실패 횟수를 실패 유형별로 증가시킵니다.
     *
     * <p>1. Redis 종류와 실패 유형을 Prometheus tag 값으로 정규화합니다.
     * <br>2. `timeout`, `connection`은 Redis 가용성 alert 실패율 계산에 포함됩니다.
     * <br>3. `non_retryable`은 별도 관측용으로만 남기고 alert 실패율 계산에서는 제외합니다.
     */
    public void incrementFailure(RedisTarget redisTarget, FailureKind failureKind) {
        meterRegistry.counter(
                "traffic_redis_failures_total",
                "redis", normalize(redisTarget),
                "kind", normalize(failureKind)
        ).increment();
    }

    /**
     * 능동 PING 결과를 gauge 상태 holder에 반영합니다.
     *
     * <p>1. Redis 종류에 해당하는 `up`/`failures` holder를 조회합니다.
     * <br>2. PING 성공이면 `up=1`, 연속 실패 횟수 `0`으로 복구합니다.
     * <br>3. PING 실패이면 `up=0`, 연속 실패 횟수를 1 증가시킵니다.
     */
    public void recordPingResult(RedisTarget redisTarget, boolean up) {
        AtomicInteger pingUp = pingUpGauges.get(redisTarget);
        AtomicInteger failures = consecutivePingFailures.get(redisTarget);
        if (pingUp == null || failures == null) {
            return;
        }

        pingUp.set(up ? 1 : 0);
        if (up) {
            failures.set(0);
        } else {
            failures.incrementAndGet();
        }
    }

    /**
     * Redis 종류 tag를 안전하게 변환합니다.
     *
     * <p>1. null 입력은 metric tag 누락을 막기 위해 `unknown`으로 보정합니다.
     * <br>2. 정상 enum 값은 Prometheus label 관례에 맞춰 소문자로 변환합니다.
     */
    private String normalize(RedisTarget redisTarget) {
        if (redisTarget == null) {
            return "unknown";
        }
        return redisTarget.tagValue();
    }

    /**
     * 실패 유형 tag를 안전하게 변환합니다.
     *
     * <p>1. null 입력은 가용성 실패율에 섞이지 않도록 `non_retryable`로 보정합니다.
     * <br>2. 정상 enum 값은 Prometheus label 관례에 맞춰 소문자로 변환합니다.
     */
    private String normalize(FailureKind failureKind) {
        if (failureKind == null) {
            return FailureKind.NON_RETRYABLE.tagValue();
        }
        return failureKind.tagValue();
    }

    public enum RedisTarget {
        CACHE,
        STREAMS;

        /**
         * Prometheus label 값으로 사용할 소문자 Redis 종류명을 반환합니다.
         */
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum FailureKind {
        TIMEOUT,
        CONNECTION,
        NON_RETRYABLE;

        /**
         * Prometheus label 값으로 사용할 소문자 실패 유형명을 반환합니다.
         */
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
