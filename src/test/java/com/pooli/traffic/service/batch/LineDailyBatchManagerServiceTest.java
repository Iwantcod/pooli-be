package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

@ExtendWith(MockitoExtension.class)
class LineDailyBatchManagerServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);
    private static final String MANAGER_INSTANCE_ID = "manager-1";

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @Mock
    private LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    @InjectMocks
    private LineDailyBatchManagerService lineDailyBatchManagerService;

    @Test
    @DisplayName("manager는 target row를 chunk insert한 뒤 target insert 완료와 usage sync 시작을 순서대로 수행한다")
    void insertsTargetRowsCompletesTargetInsertAndStartsUsageSync() {
        LineDailyBatchJob targetInsertBatch = batchJob(1L, BatchName.LINE_DAILY_TARGET_INSERT_BATCH);
        LineDailyBatchJob usageSyncBatch = batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(true, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(true, usageSyncBatch));
        when(lineDailyBatchJobService.startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID))
                .thenReturn(true);
        when(lineDailyBatchTargetMapper.selectMaxLineIdByUsageDate(USAGE_DATE)).thenReturn(20L);
        when(lineDailyBatchTargetMapper.selectActiveLineIdsAfter(20L, 1000))
                .thenReturn(List.of(30L, 40L));
        when(lineDailyBatchTargetMapper.selectActiveLineIdsAfter(40L, 1000))
                .thenReturn(List.of());
        when(lineDailyBatchTargetMapper.countByUsageDate(USAGE_DATE)).thenReturn(4L);
        when(lineDailyBatchJobService.completeRunningTargetInsertBatch(targetInsertBatch, 4L))
                .thenReturn(true);
        when(lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                4L,
                MANAGER_INSTANCE_ID
        )).thenReturn(true);

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertTrue(workerStartAllowed);
        InOrder inOrder = inOrder(lineDailyBatchJobService, lineDailyBatchTargetMapper);
        inOrder.verify(lineDailyBatchJobService).createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        );
        inOrder.verify(lineDailyBatchJobService).createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        );
        inOrder.verify(lineDailyBatchJobService).startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID);
        inOrder.verify(lineDailyBatchTargetMapper).selectMaxLineIdByUsageDate(USAGE_DATE);
        inOrder.verify(lineDailyBatchTargetMapper).selectActiveLineIdsAfter(20L, 1000);
        inOrder.verify(lineDailyBatchTargetMapper).insertIgnoreTargetRows(USAGE_DATE, List.of(30L, 40L));
        inOrder.verify(lineDailyBatchTargetMapper).selectActiveLineIdsAfter(40L, 1000);
        inOrder.verify(lineDailyBatchTargetMapper).countByUsageDate(USAGE_DATE);
        inOrder.verify(lineDailyBatchJobService).completeRunningTargetInsertBatch(targetInsertBatch, 4L);
        inOrder.verify(lineDailyBatchJobService).startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                4L,
                MANAGER_INSTANCE_ID
        );
        verify(lineDailyBatchJobService, never()).startPendingBatch(usageSyncBatch, MANAGER_INSTANCE_ID);
    }

    @Test
    @DisplayName("기존 RUNNING target insert batch는 최대 line_id 이후부터 재개한다")
    void resumesRunningTargetInsertBatchAfterMaxTargetLineId() {
        LineDailyBatchJob targetInsertBatch =
                batchJob(1L, BatchName.LINE_DAILY_TARGET_INSERT_BATCH, LineDailyBatchStatus.RUNNING);
        LineDailyBatchJob usageSyncBatch = batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, usageSyncBatch));
        when(lineDailyBatchTargetMapper.selectMaxLineIdByUsageDate(USAGE_DATE)).thenReturn(40L);
        when(lineDailyBatchTargetMapper.selectActiveLineIdsAfter(40L, 1000))
                .thenReturn(List.of(50L));
        when(lineDailyBatchTargetMapper.selectActiveLineIdsAfter(50L, 1000))
                .thenReturn(List.of());
        when(lineDailyBatchTargetMapper.countByUsageDate(USAGE_DATE)).thenReturn(5L);
        when(lineDailyBatchJobService.completeRunningTargetInsertBatch(targetInsertBatch, 5L))
                .thenReturn(true);
        when(lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                5L,
                MANAGER_INSTANCE_ID
        )).thenReturn(true);

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertTrue(workerStartAllowed);
        InOrder inOrder = inOrder(lineDailyBatchJobService, lineDailyBatchTargetMapper);
        inOrder.verify(lineDailyBatchJobService).startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID);
        inOrder.verify(lineDailyBatchTargetMapper).selectMaxLineIdByUsageDate(USAGE_DATE);
        inOrder.verify(lineDailyBatchTargetMapper).selectActiveLineIdsAfter(40L, 1000);
        inOrder.verify(lineDailyBatchTargetMapper).insertIgnoreTargetRows(USAGE_DATE, List.of(50L));
        inOrder.verify(lineDailyBatchTargetMapper).selectActiveLineIdsAfter(50L, 1000);
        inOrder.verify(lineDailyBatchTargetMapper).countByUsageDate(USAGE_DATE);
        inOrder.verify(lineDailyBatchJobService).completeRunningTargetInsertBatch(targetInsertBatch, 5L);
        inOrder.verify(lineDailyBatchJobService).startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                5L,
                MANAGER_INSTANCE_ID
        );
    }

    @Test
    @DisplayName("완료된 target insert batch 재시도는 target row 생성 없이 usage sync batch를 시작한다")
    void startsUsageSyncWhenTargetInsertBatchAlreadyCompleted() {
        LineDailyBatchJob targetInsertBatch = batchJob(
                1L,
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                LineDailyBatchStatus.COMPLETED
        ).toBuilder()
                .targetCount(5L)
                .successCount(5L)
                .build();
        LineDailyBatchJob usageSyncBatch = batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, usageSyncBatch));
        when(lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                5L,
                MANAGER_INSTANCE_ID
        )).thenReturn(true);

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertTrue(workerStartAllowed);
        verify(lineDailyBatchJobService, never()).startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID);
        verify(lineDailyBatchTargetMapper, never()).selectMaxLineIdByUsageDate(
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchTargetMapper, never()).insertIgnoreTargetRows(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchJobService, never()).completeRunningTargetInsertBatch(targetInsertBatch, 5L);
        verify(lineDailyBatchJobService).startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                5L,
                MANAGER_INSTANCE_ID
        );
    }

    @Test
    @DisplayName("완료된 target insert batch 재시도에서 usage sync batch가 이미 RUNNING이면 worker 시작을 허용한다")
    void allowsWorkerStartWhenTargetInsertCompletedAndUsageSyncAlreadyRunning() {
        LineDailyBatchJob targetInsertBatch = batchJob(
                1L,
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                LineDailyBatchStatus.COMPLETED
        ).toBuilder()
                .targetCount(5L)
                .successCount(5L)
                .build();
        LineDailyBatchJob usageSyncBatch =
                batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH, LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, usageSyncBatch));

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertTrue(workerStartAllowed);
        verify(lineDailyBatchJobService, never()).startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID);
        verify(lineDailyBatchTargetMapper, never()).selectMaxLineIdByUsageDate(
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchTargetMapper, never()).insertIgnoreTargetRows(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchJobService, never()).completeRunningTargetInsertBatch(targetInsertBatch, 5L);
        verify(lineDailyBatchJobService, never()).startPendingUsageSyncBatchWithTargetCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("target insert batch RUNNING 전환에 실패하면 target row를 생성하지 않는다")
    void doesNotInsertTargetsWhenTargetInsertBatchDidNotStart() {
        LineDailyBatchJob targetInsertBatch = batchJob(1L, BatchName.LINE_DAILY_TARGET_INSERT_BATCH);
        LineDailyBatchJob usageSyncBatch = batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(false, usageSyncBatch));
        when(lineDailyBatchJobService.startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID))
                .thenReturn(false);

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertFalse(workerStartAllowed);
        verify(lineDailyBatchTargetMapper, never()).selectMaxLineIdByUsageDate(
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchTargetMapper, never()).selectActiveLineIdsAfter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(lineDailyBatchTargetMapper, never()).insertIgnoreTargetRows(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchJobService, never()).startPendingUsageSyncBatchWithTargetCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("usage sync batch RUNNING 전환에 실패하면 worker 시작을 허용하지 않는다")
    void doesNotAllowWorkerStartWhenUsageSyncBatchDidNotStart() {
        LineDailyBatchJob targetInsertBatch = batchJob(1L, BatchName.LINE_DAILY_TARGET_INSERT_BATCH);
        LineDailyBatchJob usageSyncBatch = batchJob(2L, BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(true, targetInsertBatch));
        when(lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(new LineDailyBatchJobCreateResult(true, usageSyncBatch));
        when(lineDailyBatchJobService.startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID))
                .thenReturn(true);
        when(lineDailyBatchTargetMapper.selectMaxLineIdByUsageDate(USAGE_DATE)).thenReturn(0L);
        when(lineDailyBatchTargetMapper.selectActiveLineIdsAfter(0L, 1000))
                .thenReturn(List.of());
        when(lineDailyBatchTargetMapper.countByUsageDate(USAGE_DATE)).thenReturn(0L);
        when(lineDailyBatchJobService.completeRunningTargetInsertBatch(targetInsertBatch, 0L))
                .thenReturn(true);
        when(lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                usageSyncBatch,
                0L,
                MANAGER_INSTANCE_ID
        )).thenReturn(false);

        boolean workerStartAllowed = lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        assertFalse(workerStartAllowed);
    }

    private LineDailyBatchJob batchJob(Long id, BatchName batchName) {
        return batchJob(id, batchName, LineDailyBatchStatus.PENDING);
    }

    private LineDailyBatchJob batchJob(Long id, BatchName batchName, LineDailyBatchStatus status) {
        return LineDailyBatchJob.builder()
                .id(id)
                .batchName(batchName)
                .usageDate(USAGE_DATE)
                .status(status)
                .targetCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .skippedCount(0L)
                .build();
    }
}
