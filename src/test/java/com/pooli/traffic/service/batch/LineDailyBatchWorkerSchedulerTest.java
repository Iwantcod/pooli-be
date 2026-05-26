package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;

@ExtendWith(MockitoExtension.class)
class LineDailyBatchWorkerSchedulerTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @Mock
    private LineDailyUsageSyncWorkerService lineDailyUsageSyncWorkerService;

    @Mock
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("usage sync batch가 RUNNING이 아니면 1초와 jitter 이후 다시 확인한다")
    void reschedulesStartCheckWhenUsageSyncBatchIsNotRunning() {
        LineDailyBatchWorkerScheduler scheduler = new LineDailyBatchWorkerScheduler(
                lineDailyBatchJobService,
                lineDailyUsageSyncWorkerService,
                taskScheduler
        );
        when(lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE)).thenReturn(null);

        Instant before = Instant.now();
        scheduler.runStartCheckCycle(USAGE_DATE);
        Instant after = Instant.now();

        verify(lineDailyUsageSyncWorkerService, never()).run(any());
        ArgumentCaptor<Instant> scheduleAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), scheduleAtCaptor.capture());
        assertFalse(scheduleAtCaptor.getValue().isBefore(
                before.plusMillis(LineDailyBatchWorkerScheduler.START_CHECK_DELAY_MS)
        ));
        assertFalse(scheduleAtCaptor.getValue().isAfter(
                after.plusMillis(
                        LineDailyBatchWorkerScheduler.START_CHECK_DELAY_MS
                                + LineDailyBatchWorkerScheduler.START_CHECK_JITTER_BOUND_MS
                )
        ));
    }

    @Test
    @DisplayName("usage sync batch가 RUNNING이고 처리할 row가 있으면 즉시 다음 worker cycle을 예약한다")
    void startsWorkerWhenUsageSyncBatchIsRunning() {
        LineDailyBatchWorkerScheduler scheduler = new LineDailyBatchWorkerScheduler(
                lineDailyBatchJobService,
                lineDailyUsageSyncWorkerService,
                taskScheduler
        );
        LineDailyBatchJob running = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE)).thenReturn(running);
        when(lineDailyUsageSyncWorkerService.run(running))
                .thenReturn(LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY);

        scheduler.runStartCheckCycle(USAGE_DATE);

        verify(lineDailyUsageSyncWorkerService).run(running);
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("usage sync batch가 RUNNING이고 non-terminal row만 남아 있으면 1분 뒤 worker cycle을 예약한다")
    void reschedulesWorkerAfterEmptyPollDelayWhenNonTerminalTargetsRemain() {
        LineDailyBatchWorkerScheduler scheduler = new LineDailyBatchWorkerScheduler(
                lineDailyBatchJobService,
                lineDailyUsageSyncWorkerService,
                taskScheduler
        );
        LineDailyBatchJob running = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE)).thenReturn(running);
        when(lineDailyUsageSyncWorkerService.run(running))
                .thenReturn(LineDailyUsageSyncWorkerRunResult.WAIT_FOR_EMPTY_POLL);

        Instant before = Instant.now();
        scheduler.runStartCheckCycle(USAGE_DATE);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> scheduleAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), scheduleAtCaptor.capture());
        assertFalse(scheduleAtCaptor.getValue().isBefore(
                before.plusMillis(LineDailyBatchWorkerScheduler.EMPTY_POLL_DELAY_MS)
        ));
        assertFalse(scheduleAtCaptor.getValue().isAfter(
                after.plusMillis(LineDailyBatchWorkerScheduler.EMPTY_POLL_DELAY_MS)
        ));
    }

    @Test
    @DisplayName("usage sync batch 완료 판단 이후에는 worker cycle을 재예약하지 않는다")
    void doesNotRescheduleWorkerWhenWorkerStops() {
        LineDailyBatchWorkerScheduler scheduler = new LineDailyBatchWorkerScheduler(
                lineDailyBatchJobService,
                lineDailyUsageSyncWorkerService,
                taskScheduler
        );
        LineDailyBatchJob running = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE)).thenReturn(running);
        when(lineDailyUsageSyncWorkerService.run(running)).thenReturn(LineDailyUsageSyncWorkerRunResult.STOP);

        scheduler.runStartCheckCycle(USAGE_DATE);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("manager lock 미획득 서버의 worker 시작은 같은 usage_date로 첫 확인을 예약한다")
    void startForUsageDateSchedulesFirstCheckImmediately() {
        LineDailyBatchWorkerScheduler scheduler = new LineDailyBatchWorkerScheduler(
                lineDailyBatchJobService,
                lineDailyUsageSyncWorkerService,
                taskScheduler
        );
        LineDailyBatchJob running = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE)).thenReturn(running);
        when(lineDailyUsageSyncWorkerService.run(running)).thenReturn(LineDailyUsageSyncWorkerRunResult.STOP);

        Instant before = Instant.now();
        scheduler.startForUsageDate(USAGE_DATE);
        Instant after = Instant.now();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> scheduleAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), scheduleAtCaptor.capture());
        assertFalse(scheduleAtCaptor.getValue().isBefore(before));
        assertFalse(scheduleAtCaptor.getValue().isAfter(after));

        runnableCaptor.getValue().run();

        verify(lineDailyBatchJobService).findRunningUsageSyncBatch(USAGE_DATE);
        verify(lineDailyUsageSyncWorkerService).run(running);
    }
}
