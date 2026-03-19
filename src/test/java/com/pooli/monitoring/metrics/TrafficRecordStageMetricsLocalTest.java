package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRecordStageMetricsLocalTest {

    @Test
    @DisplayName("records stage/total latency and result counters in local profile metrics implementation")
    void recordStageTotalAndResultMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficRecordStageMetricsLocal metrics = new TrafficRecordStageMetricsLocal(meterRegistry);

        metrics.recordStageLatency("parse_validate", 12L);
        metrics.recordStageLatency("ack", 3L);
        metrics.recordTotalLatency(20L);
        metrics.incrementResult("success");
        metrics.incrementResult("success");
        metrics.incrementResult("dlq");

        Timer parseTimer = meterRegistry.find("traffic_stream_handle_record_stage_latency")
                .tag("stage", "parse_validate")
                .timer();
        Timer ackTimer = meterRegistry.find("traffic_stream_handle_record_stage_latency")
                .tag("stage", "ack")
                .timer();
        Timer totalTimer = meterRegistry.find("traffic_stream_handle_record_total_latency").timer();
        Counter successCounter = meterRegistry.find("traffic_stream_handle_record_result_total")
                .tag("result", "success")
                .counter();
        Counter dlqCounter = meterRegistry.find("traffic_stream_handle_record_result_total")
                .tag("result", "dlq")
                .counter();

        assertThat(parseTimer).isNotNull();
        assertThat(parseTimer.count()).isEqualTo(1L);
        assertThat(ackTimer).isNotNull();
        assertThat(ackTimer.count()).isEqualTo(1L);
        assertThat(totalTimer).isNotNull();
        assertThat(totalTimer.count()).isEqualTo(1L);
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(2.0);
        assertThat(dlqCounter).isNotNull();
        assertThat(dlqCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("normalizes unexpected stage/result tags to safe low-cardinality defaults")
    void normalizeUnexpectedTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficRecordStageMetricsLocal metrics = new TrafficRecordStageMetricsLocal(meterRegistry);

        metrics.recordStageLatency("line-11-family-22", 1L);
        metrics.recordStageLatency(null, -3L);
        metrics.incrementResult("");
        metrics.incrementResult("unexpected-result");

        Timer unknownStageTimer = meterRegistry.find("traffic_stream_handle_record_stage_latency")
                .tag("stage", "unknown")
                .timer();
        Counter failedCounter = meterRegistry.find("traffic_stream_handle_record_result_total")
                .tag("result", "failed")
                .counter();

        assertThat(unknownStageTimer).isNotNull();
        assertThat(unknownStageTimer.count()).isEqualTo(2L);
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(2.0);
        assertThat(meterRegistry.find("traffic_stream_handle_record_stage_latency")
                .tag("stage", "line-11-family-22")
                .timer())
                .isNull();
        assertThat(meterRegistry.find("traffic_stream_handle_record_result_total")
                .tag("result", "unexpected-result")
                .counter())
                .isNull();
    }
}
