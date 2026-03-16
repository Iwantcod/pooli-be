package com.pooli.monitoring.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.enums.TrafficFinalStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class TrafficEventProcessingMetrics {

    private final Timer eventProcessLatency;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> resultCounters = new ConcurrentHashMap<>();

    public TrafficEventProcessingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.eventProcessLatency = Timer.builder("traffic_event_process_latency")
                .description("Latency of a single traffic event processing cycle in milliseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void recordProcessLatency(long durationMs) {
        eventProcessLatency.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    public void incrementResult(TrafficFinalStatus finalStatus) {
        String result = toResultTag(finalStatus);
        Counter counter = resultCounters.computeIfAbsent(result, key ->
                Counter.builder("traffic_event_result_total")
                        .description("Total number of processed traffic events by final result")
                        .tag("result", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    private String toResultTag(TrafficFinalStatus finalStatus) {
        if (finalStatus == null) {
            return "failed";
        }

        return switch (finalStatus) {
            case SUCCESS -> "success";
            case PARTIAL_SUCCESS -> "partial";
            case FAILED -> "failed";
        };
    }
}
