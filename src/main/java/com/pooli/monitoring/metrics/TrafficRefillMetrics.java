package com.pooli.monitoring.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrafficRefillMetrics {
	private final MeterRegistry meterRegistry;

    @PostConstruct
    void init() {
        // no-op
    }

    public void increment(String poolType, String result) {
    	
    	meterRegistry.counter(
                "traffic_refill_total",
                "pool_type", poolType,
                "result", result
        ).increment();
    	
    }

}
