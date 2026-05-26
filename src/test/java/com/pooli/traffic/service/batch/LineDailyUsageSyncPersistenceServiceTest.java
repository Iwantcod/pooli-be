package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.mapper.DailyAppUsageBatchInsertRow;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;
import com.pooli.traffic.mapper.TrafficDailyUsageBatchMapper;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncPersistenceServiceTest {

    private static final Long BATCH_JOB_ID = 20L;
    private static final String WORKER_ID = "worker-1";
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private TrafficDailyUsageBatchMapper trafficDailyUsageBatchMapper;

    @Mock
    private LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    @Mock
    private LineDailyBatchJobMapper lineDailyBatchJobMapper;

    @InjectMocks
    private LineDailyUsageSyncPersistenceService lineDailyUsageSyncPersistenceService;

    @Test
    @DisplayName("사용량 snapshot이 있으면 존재하는 usage만 insert하고 DONE count를 증가시킨다")
    void insertsExistingUsageAndIncrementsSuccessCountWhenDoneTransitionSucceeds() {
        LineDailyBatchTarget target = target();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(
                100L,
                List.of(
                        new DailyAppUsage(30, 70L, 20L, 10L),
                        new DailyAppUsage(31, 40L, 5L, 0L)
                ),
                new DailySharedUsage(40L, 20L)
        );
        when(lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                LineDailyBatchTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.DONE
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                BATCH_JOB_ID,
                target,
                usage,
                WORKER_ID
        );

        verify(trafficDailyUsageBatchMapper).insertDailyTotalUsage(USAGE_DATE, 10L, 100L);
        verify(trafficDailyUsageBatchMapper).insertDailyAppUsages(
                USAGE_DATE,
                10L,
                List.of(
                        new DailyAppUsageBatchInsertRow(30, 70L, 20L, 10L),
                        new DailyAppUsageBatchInsertRow(31, 40L, 5L, 0L)
                )
        );
        verify(trafficDailyUsageBatchMapper).insertFamilySharedDailyUsage(USAGE_DATE, 40L, 10L, 20L);
        verify(lineDailyBatchJobMapper).incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.DONE
        );
    }

    @Test
    @DisplayName("사용량 snapshot이 비어 있으면 insert 없이 SKIPPED count를 증가시킨다")
    void skipsTargetWithoutUsageInsertWhenSnapshotIsEmpty() {
        LineDailyBatchTarget target = target();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(null, List.of(), null);
        when(lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                LineDailyBatchTargetStatus.SKIPPED,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.SKIPPED
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                BATCH_JOB_ID,
                target,
                usage,
                WORKER_ID
        );

        verify(trafficDailyUsageBatchMapper, never()).insertDailyTotalUsage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchJobMapper).incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.SKIPPED
        );
    }

    @Test
    @DisplayName("terminal 전환이 실패하면 count를 증가시키지 않고 예외를 던진다")
    void doesNotIncrementCountWhenTerminalTransitionFails() {
        LineDailyBatchTarget target = target();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(100L, List.of(), null);
        when(lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                LineDailyBatchTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                        BATCH_JOB_ID,
                        target,
                        usage,
                        WORKER_ID
                )
        );

        verify(lineDailyBatchJobMapper, never()).incrementUsageSyncProcessedCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("FAILED terminal 전환 성공 시 failed_count를 증가시킨다")
    void incrementsFailedCountWhenFailedTransitionSucceeds() {
        LineDailyBatchTarget target = target();
        when(lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                LineDailyBatchTargetStatus.FAILED,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.FAILED
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.completeFailedTarget(BATCH_JOB_ID, target, WORKER_ID);

        verify(lineDailyBatchJobMapper).incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.FAILED
        );
    }

    @Test
    @DisplayName("metadata count 증가가 실패하면 terminal 전환 후 예외를 던진다")
    void throwsWhenProcessedCountIncrementFails() {
        LineDailyBatchTarget target = target();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(null, List.of(), null);
        when(lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                LineDailyBatchTargetStatus.SKIPPED,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.SKIPPED
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                        BATCH_JOB_ID,
                        target,
                        usage,
                        WORKER_ID
                )
        );
    }

    private LineDailyBatchTarget target() {
        return LineDailyBatchTarget.builder()
                .id(1L)
                .usageDate(USAGE_DATE)
                .lineId(10L)
                .build();
    }
}
