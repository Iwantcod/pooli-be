package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.service.batch.LineDailyBatchJobService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LineDailyUsageSyncBatchMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Test
    @DisplayName("RUNNING usage sync batch의 failed_count와 경과 시간을 gauge로 노출한다")
    void exposesRunningBatchMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LineDailyBatchJobService batchJobService = org.mockito.Mockito.mock(LineDailyBatchJobService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-25T21:30:00Z"), KST);
        when(batchJobService.findLatestUsageSyncBatch()).thenReturn(LineDailyBatchJob.builder()
                .id(10L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .runStartedAt(LocalDateTime.of(2026, 5, 26, 6, 0))
                .failedCount(2L)
                .build());

        LineDailyUsageSyncBatchMetrics metrics =
                new LineDailyUsageSyncBatchMetrics(meterRegistry, batchJobService, clock);
        metrics.init();

        assertThat(meterRegistry.get("batch_daily_usage_sync_failed_count").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("batch_daily_usage_sync_status").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("batch_daily_usage_sync_run_duration_seconds").gauge().value())
                .isEqualTo(1800.0);
    }

    @Test
    @DisplayName("RUNNING batch가 아니면 failed_count와 경과 시간은 0으로 둔다")
    void resetsRuntimeMetricsWhenLatestBatchIsNotRunning() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LineDailyBatchJobService batchJobService = org.mockito.Mockito.mock(LineDailyBatchJobService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-25T21:30:00Z"), KST);
        when(batchJobService.findLatestUsageSyncBatch()).thenReturn(LineDailyBatchJob.builder()
                .id(10L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.COMPLETED)
                .failedCount(2L)
                .build());

        LineDailyUsageSyncBatchMetrics metrics =
                new LineDailyUsageSyncBatchMetrics(meterRegistry, batchJobService, clock);
        metrics.init();

        assertThat(meterRegistry.get("batch_daily_usage_sync_failed_count").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("batch_daily_usage_sync_status").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("batch_daily_usage_sync_run_duration_seconds").gauge().value()).isEqualTo(0.0);
    }
}
