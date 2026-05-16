package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TrafficSharedPoolContributionCleanupMetricsTest {

    @Test
    @DisplayName("cleanup failure counter uses phase path reason tags")
    void cleanupFailureCounterUsesTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficSharedPoolContributionCleanupMetrics metrics =
                new TrafficSharedPoolContributionCleanupMetrics(meterRegistry);

        metrics.incrementFailure("request", "timeout");

        assertThat(meterRegistry.find("traffic_shared_pool_contribution_cleanup_failures_total")
                .tag("phase", "after_commit")
                .tag("path", "request")
                .tag("reason", "timeout")
                .counter()
                .count()
        ).isEqualTo(1.0);
    }
}
