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
    private final AtomicInteger currentWorkerIdleThreads = new AtomicInteger(0);
    private final AtomicInteger currentWorkerQueueSize = new AtomicInteger(0);
//    private Timer processLatency;

    @PostConstruct
    void init() {
        generatedRequests = Counter.builder("traffic_generated_requests_total")
                .description("Total generated traffic events")
                .register(meterRegistry);

        Gauge.builder("traffic_generator_process_tps", currentSecondProcessed, AtomicInteger::get)
                .description("Traffic generator events processed per second")
                .register(meterRegistry);

        Gauge.builder("traffic_stream_worker_idle_threads", currentWorkerIdleThreads, AtomicInteger::get)
                .description("Current number of idle worker threads in stream consumer")
                .register(meterRegistry);

        Gauge.builder("traffic_stream_worker_queue_size", currentWorkerQueueSize, AtomicInteger::get)
                .description("Current number of queued tasks waiting for worker execution")
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

    /**
     * 현재 유휴 워커 스레드 개수를 메트릭으로 반영합니다.
     */
    public void updateWorkerIdleThreads(int idleThreadCount) {
        currentWorkerIdleThreads.set(Math.max(0, idleThreadCount));
    }

    /**
     * 현재 워커 대기열에 적재된 작업 개수를 메트릭으로 반영합니다.
     */
    public void updateWorkerQueueSize(int queueSize) {
        currentWorkerQueueSize.set(Math.max(0, queueSize));
    }

//    public void recordProcessLatency(long durationMs) {
//        processLatency.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
//    }
}
