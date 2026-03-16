package com.pooli.monitoring.metrics;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * deduct Redis 장애 대응(재시도/fallback/replay) 관측 지표를 제공하는 컴포넌트입니다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductFallbackMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong replayBacklogGauge = new AtomicLong(0L);

    /**
     * Redis 재시도 횟수를 집계합니다.
     */
    public void incrementRedisRetry(String poolType, int retryAttempt, String reason) {
        meterRegistry.counter(
                "traffic_deduct_redis_retry_total",
                "pool_type", normalizePoolType(poolType),
                "retry_attempt", String.valueOf(Math.max(1, retryAttempt)),
                "reason", normalizeReason(reason)
        ).increment();
    }

    /**
     * DB fallback 전환 횟수를 집계합니다.
     */
    public void incrementDbFallback(String poolType, String reason) {
        meterRegistry.counter(
                "traffic_deduct_db_fallback_total",
                "pool_type", normalizePoolType(poolType),
                "reason", normalizeReason(reason)
        ).increment();
    }

    /**
     * usage delta replay 결과를 집계합니다.
     */
    public void incrementReplayResult(String result) {
        meterRegistry.counter(
                "traffic_redis_usage_replay_total",
                "result", normalizeReason(result)
        ).increment();
    }

    /**
     * usage delta backlog gauge를 갱신합니다.
     */
    public void updateReplayBacklog(long backlogCount) {
        ensureReplayBacklogGaugeRegistered();
        replayBacklogGauge.set(Math.max(0L, backlogCount));
    }

    /**
     * backlog gauge를 지연 등록합니다.
     */
    private void ensureReplayBacklogGaugeRegistered() {
        // 중복 등록을 피하기 위해 현재 등록 여부를 먼저 확인한다.
        if (meterRegistry.find("traffic_redis_usage_replay_backlog").gauge() != null) {
            return;
        }

        Gauge.builder("traffic_redis_usage_replay_backlog", replayBacklogGauge, AtomicLong::get)
                .description("Pending redis usage delta replay backlog")
                .register(meterRegistry);
    }

    private String normalizePoolType(String poolType) {
        if (poolType == null || poolType.isBlank()) {
            return "unknown";
        }
        return poolType.toLowerCase(Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.toLowerCase(Locale.ROOT);
    }
}
