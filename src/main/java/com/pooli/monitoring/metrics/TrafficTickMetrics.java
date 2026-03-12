package com.pooli.monitoring.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrafficTickMetrics {
	
	private final MeterRegistry meterRegistry;
    private Timer tickLagTimer;

    @PostConstruct
    void init() {
        tickLagTimer = Timer.builder("traffic_tick_lag_seconds")
                .description("Tick start lag from scheduled time")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void recordLagMillis(long lagMs) {
        if (lagMs <= 0) {
            return;
        }
        tickLagTimer.record(Duration.ofMillis(lagMs));
    }

}
