package com.pooli.monitoring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrafficGeneratorMetrics {

    private final MeterRegistry meterRegistry;

    private Counter generatedRequests;

    @PostConstruct
    void init() {
        generatedRequests = Counter.builder("traffic_generated_requests_total")
                .description("Total generated traffic events")
                .register(meterRegistry);
    }

    public void incrementGenerated() {
        generatedRequests.increment();
    }
}