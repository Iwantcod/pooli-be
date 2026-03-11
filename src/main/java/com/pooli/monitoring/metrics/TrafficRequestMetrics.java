package com.pooli.monitoring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrafficRequestMetrics {

    private final MeterRegistry meterRegistry;

    private Counter enqueueCounter;

    @PostConstruct
    void init() {
        enqueueCounter = Counter.builder("traffic_stream_requests_total")
                .description("Total traffic generation requests")
                .register(meterRegistry);
    }

    public void incrementRequest() {
        enqueueCounter.increment();
    }
}