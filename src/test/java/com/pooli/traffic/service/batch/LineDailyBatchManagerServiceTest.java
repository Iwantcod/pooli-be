package com.pooli.traffic.service.batch;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

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

@ExtendWith(MockitoExtension.class)
class LineDailyBatchManagerServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);
    private static final String MANAGER_INSTANCE_ID = "manager-1";

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @InjectMocks
    private LineDailyBatchManagerService lineDailyBatchManagerService;

    @Test
    @DisplayName("manager는 같은 usage_date로 두 metadata를 준비한 뒤 target insert batch만 RUNNING 전환한다")
    void preparesBothMetadataAndStartsOnlyTargetInsertBatch() {
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

        lineDailyBatchManagerService.run(USAGE_DATE, MANAGER_INSTANCE_ID);

        InOrder inOrder = inOrder(lineDailyBatchJobService);
        inOrder.verify(lineDailyBatchJobService).createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        );
        inOrder.verify(lineDailyBatchJobService).createPendingForAutomaticRunIfAbsent(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        );
        inOrder.verify(lineDailyBatchJobService).startPendingBatch(targetInsertBatch, MANAGER_INSTANCE_ID);
        verify(lineDailyBatchJobService, never()).startPendingBatch(usageSyncBatch, MANAGER_INSTANCE_ID);
    }

    private LineDailyBatchJob batchJob(Long id, BatchName batchName) {
        return LineDailyBatchJob.builder()
                .id(id)
                .batchName(batchName)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.PENDING)
                .targetCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .skippedCount(0L)
                .build();
    }
}
