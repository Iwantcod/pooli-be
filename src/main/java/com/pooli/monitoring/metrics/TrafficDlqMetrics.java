package com.pooli.monitoring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TrafficDlqMetrics {

    private final MeterRegistry meterRegistry;
    private Counter dlqTotal;

    // reason별 카운터를 저장
    private final ConcurrentMap<String, Counter> reasonCounters = new ConcurrentHashMap<>();

    public TrafficDlqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        dlqTotal = Counter.builder("traffic_dlq_total")
                .description("Total number of traffic messages sent to DLQ")
                .register(meterRegistry);
    }

    public void incrementDlq() {
        dlqTotal.increment();
    }

    // ✅ reason별 DLQ 기록
    public void incrementDlqByReason(String reason) {
        dlqTotal.increment(); // 총합도 증가
        if (reason == null || reason.isBlank()) reason = "unknown";

        // reason별 Counter 가져오기/생성
        Counter counter = reasonCounters.computeIfAbsent(reason, r ->
                Counter.builder("traffic_dlq_total")
                        .description("DLQ messages categorized by reason")
                        .tag("reason", r)
                        .register(meterRegistry)
        );
        counter.increment();
    }
}