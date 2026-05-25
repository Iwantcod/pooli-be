package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;

/**
 * 자동 실행 metadata 생성 절차를 고정하는 순수 단위 테스트이다.
 * DB unique key 없이도 서비스가 기존 row 조회를 먼저 수행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LineDailyBatchJobServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 24);

    @Mock
    private LineDailyBatchJobMapper lineDailyBatchJobMapper;

    @InjectMocks
    private LineDailyBatchJobService lineDailyBatchJobService;

    @Test
    @DisplayName("기존 미완료 batch가 있으면 신규 row를 생성하지 않는다")
    void returnsExistingNonTerminalBatchWithoutInsert() {
        LineDailyBatchJob existing = batchJob(LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                USAGE_DATE
        )).thenReturn(existing);

        LineDailyBatchJobCreateResult result =
                lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                        BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                        USAGE_DATE
                );

        assertFalse(result.created());
        assertSame(existing, result.batchJob());
        verify(lineDailyBatchJobMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("기존 terminal batch가 있어도 자동 신규 row를 생성하지 않는다")
    void returnsExistingTerminalBatchWithoutInsert() {
        LineDailyBatchJob existing = batchJob(LineDailyBatchStatus.COMPLETED);
        when(lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(existing);

        LineDailyBatchJobCreateResult result =
                lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                        BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                        USAGE_DATE
                );

        assertFalse(result.created());
        assertSame(existing, result.batchJob());
        verify(lineDailyBatchJobMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("기존 batch가 없으면 PENDING 상태와 line 단위 count 0으로 생성한다")
    void createsPendingBatchWhenAbsent() {
        when(lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(null);

        LineDailyBatchJobCreateResult result =
                lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(
                        BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                        USAGE_DATE
                );

        ArgumentCaptor<LineDailyBatchJob> captor = ArgumentCaptor.forClass(LineDailyBatchJob.class);
        verify(lineDailyBatchJobMapper).insert(captor.capture());
        LineDailyBatchJob inserted = captor.getValue();

        assertTrue(result.created());
        assertSame(inserted, result.batchJob());
        assertEquals(BatchName.LINE_DAILY_USAGE_SYNC_BATCH, inserted.getBatchName());
        assertEquals(USAGE_DATE, inserted.getUsageDate());
        assertEquals(LineDailyBatchStatus.PENDING, inserted.getStatus());
        assertEquals(0L, inserted.getTargetCount());
        assertEquals(0L, inserted.getSuccessCount());
        assertEquals(0L, inserted.getFailedCount());
        assertEquals(0L, inserted.getSkippedCount());
    }

    private LineDailyBatchJob batchJob(LineDailyBatchStatus status) {
        return LineDailyBatchJob.builder()
                .id(1L)
                .batchName(BatchName.LINE_DAILY_TARGET_INSERT_BATCH)
                .usageDate(USAGE_DATE)
                .status(status)
                .targetCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .skippedCount(0L)
                .build();
    }
}
