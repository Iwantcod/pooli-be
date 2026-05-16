package com.pooli.monitoring.metrics;

import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

@Component
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficSharedPoolContributionCleanupMetrics {

    private final MeterRegistry meterRegistry;

    public void incrementFailure(String path, String reason) {
        meterRegistry.counter(
                "traffic_shared_pool_contribution_cleanup_failures_total",
                "phase", "after_commit",
                "path", normalize(path),
                "reason", normalize(reason)
        ).increment();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
