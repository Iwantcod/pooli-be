package com.pooli.monitoring.metrics;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrafficGeneratorMetrics {

    private final MeterRegistry meterRegistry;

    private Counter generatedRequests;
    private final AtomicInteger currentSecondProcessed = new AtomicInteger(0);
//    private Timer processLatency;

    @PostConstruct
    void init() {
        generatedRequests = Counter.builder("traffic_generated_requests_total")
                .description("Total generated traffic events")
                .register(meterRegistry);

        Gauge.builder("traffic_generator_process_tps", currentSecondProcessed, AtomicInteger::get)
                .description("Traffic generator events processed per second")
                .register(meterRegistry);

//        processLatency = Timer.builder("traffic_generator_process_latency")
//                .description("Latency of a single traffic generator event processing in milliseconds")
//                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
//                .register(meterRegistry);

        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> currentSecondProcessed.set(0), 1, 1, TimeUnit.SECONDS);
    }

    public void incrementGenerated() {
        generatedRequests.increment();
    }

    public void incrementProcessed() {
        currentSecondProcessed.incrementAndGet();
    }

//    public void recordProcessLatency(long durationMs) {
//        processLatency.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
//    }
}