package com.pooli.monitoring.metrics;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local 프로파일에서만 활성화되는 handleRecord 상세 메트릭 구현체입니다.
 * stage/result 태그를 정규화해 고카디널리티 태그 유입을 방지합니다.
 */
@Component
@Profile("local")
public class TrafficRecordStageMetricsLocal implements TrafficRecordStageMetricsPort {

    private static final String STAGE_METRIC_NAME = "traffic_stream_handle_record_stage_latency";
    private static final String TOTAL_METRIC_NAME = "traffic_stream_handle_record_total_latency";
    private static final String RESULT_METRIC_NAME = "traffic_stream_handle_record_result_total";

    private static final Set<String> ALLOWED_STAGES = Set.of(
            "parse_validate",
            "dedupe",
            "orchestrate",
            "done_log_save",
            "ack",
            "total"
    );

    private static final Set<String> ALLOWED_RESULTS = Set.of(
            "success",
            "failed",
            "dlq",
            "deduped"
    );

    private final MeterRegistry meterRegistry;
    private final Timer totalLatencyTimer;
    private final ConcurrentMap<String, Timer> stageLatencyTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> resultCounters = new ConcurrentHashMap<>();

    public TrafficRecordStageMetricsLocal(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.totalLatencyTimer = Timer.builder(TOTAL_METRIC_NAME)
                .description("Total latency of handleRecord processing in milliseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public void recordStageLatency(String stage, long durationMs) {
        String normalizedStage = normalizeStage(stage);
        Timer timer = stageLatencyTimers.computeIfAbsent(
                normalizedStage,
                key -> Timer.builder(STAGE_METRIC_NAME)
                        .description("Stage latency inside handleRecord in milliseconds")
                        .tag("stage", key)
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry)
        );
        timer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordTotalLatency(long durationMs) {
        totalLatencyTimer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void incrementResult(String result) {
        String normalizedResult = normalizeResult(result);
        Counter counter = resultCounters.computeIfAbsent(
                normalizedResult,
                key -> Counter.builder(RESULT_METRIC_NAME)
                        .description("Result count of handleRecord processing")
                        .tag("result", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    private String normalizeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "unknown";
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_STAGES.contains(normalized)) {
            return normalized;
        }
        return "unknown";
    }

    private String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "failed";
        }
        String normalized = result.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_RESULTS.contains(normalized)) {
            return normalized;
        }
        return "failed";
    }
}
