package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.FailureKind;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.RedisTarget;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TrafficRedisAvailabilityMetricsTest {

    @Test
    @DisplayName("Redis operation and failure counters use redis/kind tags")
    void redisOperationAndFailureCountersUseTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficRedisAvailabilityMetrics metrics = new TrafficRedisAvailabilityMetrics(meterRegistry);
        metrics.init();

        metrics.incrementOperation(RedisTarget.CACHE);
        metrics.incrementFailure(RedisTarget.CACHE, FailureKind.TIMEOUT);
        metrics.incrementFailure(RedisTarget.STREAMS, FailureKind.NON_RETRYABLE);

        assertThat(meterRegistry.find("traffic_redis_ops_total").tag("redis", "cache").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("traffic_redis_failures_total")
                .tag("redis", "cache")
                .tag("kind", "timeout")
                .counter()
                .count()
        ).isEqualTo(1.0);
        assertThat(meterRegistry.find("traffic_redis_failures_total")
                .tag("redis", "streams")
                .tag("kind", "non_retryable")
                .counter()
                .count()
        ).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PING gauge tracks up state and consecutive failures")
    void pingGaugeTracksUpAndConsecutiveFailures() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficRedisAvailabilityMetrics metrics = new TrafficRedisAvailabilityMetrics(meterRegistry);
        metrics.init();

        metrics.recordPingResult(RedisTarget.STREAMS, false);
        metrics.recordPingResult(RedisTarget.STREAMS, false);

        assertThat(meterRegistry.find("traffic_redis_ping_up").tag("redis", "streams").gauge().value())
                .isZero();
        assertThat(meterRegistry.find("traffic_redis_ping_failures").tag("redis", "streams").gauge().value())
                .isEqualTo(2.0);

        metrics.recordPingResult(RedisTarget.STREAMS, true);

        assertThat(meterRegistry.find("traffic_redis_ping_up").tag("redis", "streams").gauge().value())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("traffic_redis_ping_failures").tag("redis", "streams").gauge().value())
                .isZero();
    }
}

