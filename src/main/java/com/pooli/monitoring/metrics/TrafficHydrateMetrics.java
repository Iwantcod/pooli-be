package com.pooli.monitoring.metrics;

import java.util.EnumMap;
import java.util.Locale;

import com.pooli.traffic.domain.enums.TrafficPoolType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficHydrateMetrics {

    private final MeterRegistry meterRegistry;

    private final EnumMap<TrafficPoolType, Counter> hydrateCounters = new EnumMap<>(TrafficPoolType.class);

    @PostConstruct
    void init() {
        for (TrafficPoolType poolType : TrafficPoolType.values()) {
            hydrateCounters.put(
                    poolType,
                    Counter.builder("traffic_hydrate_total")
                            .description("Total number of Redis miss hydrate operations")
                            .tag("pool_type", poolType.name().toLowerCase(Locale.ROOT))
                            .register(meterRegistry)
            );
        }
    }

    public void incrementHydrate(TrafficPoolType poolType) {
        Counter counter = hydrateCounters.get(poolType);
        if (counter != null) {
            counter.increment();
        }
    }

    public void incrementInvalidHydrate(TrafficPoolType poolType, String reason) {
        meterRegistry.counter(
                "traffic_hydrate_invalid_total",
                "pool_type", poolType == null ? "unknown" : poolType.name().toLowerCase(Locale.ROOT),
                "reason", reason == null || reason.isBlank() ? "unknown" : reason.toLowerCase(Locale.ROOT)
        ).increment();
    }
}
