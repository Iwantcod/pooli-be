package com.pooli.monitoring.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TrafficRequestMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer enqueueTimer;

    // 1초간 누적 요청 수
    private final AtomicInteger currentSecondRequests = new AtomicInteger(0);

    // 생성자에서 final 변수 초기화
    public TrafficRequestMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enqueueTimer = Timer.builder("traffic_stream_enqueue_latency")
                .description("Latency of enqueue API in milliseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99) // P50, P90, P95, P99
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        // TPS Gauge
        Gauge.builder("traffic_stream_requests_tps", currentSecondRequests, AtomicInteger::get)
                .description("Traffic requests per second")
                .register(meterRegistry);

        // 1초마다 요청 수 초기화
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(
                        () -> currentSecondRequests.set(0),
                        1, 1, TimeUnit.SECONDS
                );
    }

    public void incrementRequest() {
        currentSecondRequests.incrementAndGet();
    }

    public void recordEnqueueLatency(long durationMs) {
        enqueueTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}