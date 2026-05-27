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

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.mapper.DailyAppUsageBatchInsertRow;
import com.pooli.traffic.mapper.DailySharedUsageBatchInsertRow;
import com.pooli.traffic.mapper.DailyTotalUsageBatchInsertRow;
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
                List.of(
                        new DailyAppUsageBatchInsertRow(USAGE_DATE, 10L, 30, 70L, 20L, 10L),
                        new DailyAppUsageBatchInsertRow(USAGE_DATE, 10L, 31, 40L, 5L, 0L)
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
        verify(trafficDailyUsageBatchMapper, never()).insertDailyAppUsages(org.mockito.ArgumentMatchers.any());
        verify(trafficDailyUsageBatchMapper, never()).insertFamilySharedDailyUsage(
                org.mockito.ArgumentMatchers.any(),
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
    @DisplayName("bulk 처리에서 사용량이 있는 target은 DONE, 빈 snapshot target은 SKIPPED로 분류하고 count를 합산한다")
    void persistsUsagesAndCompletesTargetsInBulkWithDoneAndSkippedCounts() {
        LineDailyBatchJob batchJob = batchJob();
        LineDailyBatchTarget usageTarget = target();
        LineDailyBatchTarget skippedTarget = target().toBuilder()
                .id(2L)
                .lineId(11L)
                .build();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(
                100L,
                List.of(new DailyAppUsage(30, 70L, 20L, 10L)),
                new DailySharedUsage(40L, 20L)
        );
        LineDailyUsageReadResult emptyUsage = new LineDailyUsageReadResult(null, List.of(), null);
        when(lineDailyBatchTargetMapper.markTargetsTerminalInBulk(
                List.of(usageTarget.getId()),
                LineDailyBatchTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchTargetMapper.markTargetsTerminalInBulk(
                List.of(skippedTarget.getId()),
                LineDailyBatchTargetStatus.SKIPPED,
                WORKER_ID
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncSuccessAndSkippedCount(BATCH_JOB_ID, 1, 1))
                .thenReturn(1);

        lineDailyUsageSyncPersistenceService.persistUsagesAndCompleteTargets(
                batchJob,
                List.of(
                        new LineDailyTargetWithSnapshot(usageTarget, usage),
                        new LineDailyTargetWithSnapshot(skippedTarget, emptyUsage)
                ),
                WORKER_ID
        );

        verify(trafficDailyUsageBatchMapper).insertDailyTotalUsages(
                List.of(new DailyTotalUsageBatchInsertRow(USAGE_DATE, 10L, 100L))
        );
        verify(trafficDailyUsageBatchMapper).insertDailyAppUsages(
                List.of(new DailyAppUsageBatchInsertRow(USAGE_DATE, 10L, 30, 70L, 20L, 10L))
        );
        verify(trafficDailyUsageBatchMapper).insertFamilySharedDailyUsages(
                List.of(new DailySharedUsageBatchInsertRow(USAGE_DATE, 40L, 10L, 20L))
        );
        verify(lineDailyBatchTargetMapper).markTargetsTerminalInBulk(
                List.of(usageTarget.getId()),
                LineDailyBatchTargetStatus.DONE,
                WORKER_ID
        );
        verify(lineDailyBatchTargetMapper).markTargetsTerminalInBulk(
                List.of(skippedTarget.getId()),
                LineDailyBatchTargetStatus.SKIPPED,
                WORKER_ID
        );
        verify(lineDailyBatchJobMapper).incrementUsageSyncSuccessAndSkippedCount(BATCH_JOB_ID, 1, 1);
    }

    @Test
    @DisplayName("bulk target terminal 전환 건수가 기대 건수와 다르면 metadata count를 증가시키지 않고 예외를 던진다")
    void doesNotIncrementBulkCountWhenTerminalTransitionCountMismatches() {
        LineDailyBatchTarget target = target();
        LineDailyUsageReadResult usage = new LineDailyUsageReadResult(100L, List.of(), null);
        when(lineDailyBatchTargetMapper.markTargetsTerminalInBulk(
                List.of(target.getId()),
                LineDailyBatchTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> lineDailyUsageSyncPersistenceService.persistUsagesAndCompleteTargets(
                        batchJob(),
                        List.of(new LineDailyTargetWithSnapshot(target, usage)),
                        WORKER_ID
                )
        );

        verify(lineDailyBatchJobMapper, never()).incrementUsageSyncSuccessAndSkippedCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    @DisplayName("FAILED terminal 전환 성공 시 failed_count를 증가시킨다")
    void incrementsFailedCountWhenFailedTransitionSucceeds() {
        LineDailyBatchTarget target = target();
        when(lineDailyBatchTargetMapper.markTargetFailedIfProcessing(
                target.getId(),
                WORKER_ID,
                "WORKER_FAILED",
                null
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
    @DisplayName("재시도 가능한 실패는 retry_count를 증가시키며 RETRYABLE로 전환하고 failed_count는 증가시키지 않는다")
    void marksRetryableWithoutIncrementingFailedCountWhenRetryCountIsBelowMax() {
        LineDailyBatchTarget target = target().toBuilder()
                .retryCount(9)
                .build();
        when(lineDailyBatchTargetMapper.markTargetRetryableIfProcessing(
                target.getId(),
                WORKER_ID,
                LineDailyUsageSyncPersistenceService.MAX_TARGET_RETRY_COUNT,
                "RETRYABLE_WORKER_FAILURE",
                "timeout"
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.recordRetryableFailure(
                BATCH_JOB_ID,
                target,
                WORKER_ID,
                "RETRYABLE_WORKER_FAILURE",
                "timeout"
        );

        verify(lineDailyBatchTargetMapper).markTargetRetryableIfProcessing(
                target.getId(),
                WORKER_ID,
                LineDailyUsageSyncPersistenceService.MAX_TARGET_RETRY_COUNT,
                "RETRYABLE_WORKER_FAILURE",
                "timeout"
        );
        verify(lineDailyBatchJobMapper, never()).incrementUsageSyncProcessedCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("retry_count가 한도에 도달한 재시도 가능 실패는 retry_count 증가 없이 FAILED count를 증가시킨다")
    void marksFailedWithoutRetryIncrementWhenRetryCountReachedMax() {
        LineDailyBatchTarget target = target().toBuilder()
                .retryCount(LineDailyUsageSyncPersistenceService.MAX_TARGET_RETRY_COUNT)
                .build();
        when(lineDailyBatchTargetMapper.markTargetFailedIfProcessing(
                target.getId(),
                WORKER_ID,
                "RETRY_EXHAUSTED",
                "timeout"
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.FAILED
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.recordRetryableFailure(
                BATCH_JOB_ID,
                target,
                WORKER_ID,
                "RETRYABLE_WORKER_FAILURE",
                "timeout"
        );

        verify(lineDailyBatchTargetMapper, never()).markTargetRetryableIfProcessing(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchJobMapper).incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.FAILED
        );
    }

    @Test
    @DisplayName("자동 복구가 불가능한 실패는 retry_count와 무관하게 FAILED count를 증가시킨다")
    void marksFailedForNonRetryableFailureRegardlessOfRetryCount() {
        LineDailyBatchTarget target = target().toBuilder()
                .retryCount(0)
                .build();
        when(lineDailyBatchTargetMapper.markTargetFailedIfProcessing(
                target.getId(),
                WORKER_ID,
                "NON_RETRYABLE_WORKER_FAILURE",
                "invalid redis field"
        )).thenReturn(1);
        when(lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                BATCH_JOB_ID,
                LineDailyBatchTargetStatus.FAILED
        )).thenReturn(1);

        lineDailyUsageSyncPersistenceService.recordNonRetryableFailure(
                BATCH_JOB_ID,
                target,
                WORKER_ID,
                "NON_RETRYABLE_WORKER_FAILURE",
                "invalid redis field"
        );

        verify(lineDailyBatchTargetMapper, never()).markTargetRetryableIfProcessing(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
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

    private LineDailyBatchJob batchJob() {
        return LineDailyBatchJob.builder()
                .id(BATCH_JOB_ID)
                .usageDate(USAGE_DATE)
                .build();
    }
}
