package com.pooli.monitoring.metrics;

import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * deduct Redis 장애 대응(재시도/fallback) 관측 지표를 제공하는 컴포넌트입니다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductFallbackMetrics {

    private final MeterRegistry meterRegistry;

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
