package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TrafficGeneratorMetricsTest {

    @Test
    @DisplayName("updates worker idle/queue gauges with sanitized non-negative values")
    void updatesWorkerRuntimeGauges() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficGeneratorMetrics trafficGeneratorMetrics = new TrafficGeneratorMetrics(meterRegistry);
        trafficGeneratorMetrics.init();

        trafficGeneratorMetrics.updateWorkerIdleThreads(6);
        trafficGeneratorMetrics.updateWorkerQueueSize(11);

        assertThat(meterRegistry.get("traffic_stream_worker_idle_threads").gauge().value()).isEqualTo(6.0);
        assertThat(meterRegistry.get("traffic_stream_worker_queue_size").gauge().value()).isEqualTo(11.0);

        // 음수 입력은 관측값 왜곡을 막기 위해 0으로 보정한다.
        trafficGeneratorMetrics.updateWorkerIdleThreads(-3);
        trafficGeneratorMetrics.updateWorkerQueueSize(-7);

        assertThat(meterRegistry.get("traffic_stream_worker_idle_threads").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("traffic_stream_worker_queue_size").gauge().value()).isEqualTo(0.0);
    }
}
