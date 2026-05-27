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

    @Test
    @DisplayName("worker 시작 감지는 usage_date 기준 RUNNING usage sync batch를 조회한다")
    void findsRunningUsageSyncBatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING)
                .toBuilder()
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .build();
        when(lineDailyBatchJobMapper.selectRunningUsageSyncBatchByUsageDate(USAGE_DATE)).thenReturn(running);

        LineDailyBatchJob result = lineDailyBatchJobService.findRunningUsageSyncBatch(USAGE_DATE);

        assertSame(running, result);
        verify(lineDailyBatchJobMapper).selectRunningUsageSyncBatchByUsageDate(USAGE_DATE);
    }

    @Test
    @DisplayName("운영 재개 요청은 usage_date 기준 최신 usage sync batch를 조회한다")
    void findsLatestUsageSyncBatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING)
                .toBuilder()
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .build();
        when(lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        )).thenReturn(running);

        LineDailyBatchJob result = lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE);

        assertSame(running, result);
        verify(lineDailyBatchJobMapper).selectLatestByBatchNameAndUsageDate(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                USAGE_DATE
        );
    }

    @Test
    @DisplayName("metric collector는 최신 usage sync batch 한 건을 조회한다")
    void findsLatestUsageSyncBatchWithoutUsageDate() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING)
                .toBuilder()
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .build();
        when(lineDailyBatchJobMapper.selectLatestByBatchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH))
                .thenReturn(running);

        LineDailyBatchJob result = lineDailyBatchJobService.findLatestUsageSyncBatch();

        assertSame(running, result);
        verify(lineDailyBatchJobMapper).selectLatestByBatchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH);
    }

    @Test
    @DisplayName("PENDING batch만 RUNNING으로 전환한다")
    void startsPendingBatch() {
        LineDailyBatchJob pending = batchJob(LineDailyBatchStatus.PENDING);
        when(lineDailyBatchJobMapper.updateStatusFromPending(
                pending.getId(),
                LineDailyBatchStatus.RUNNING,
                "manager-1"
        )).thenReturn(1);

        boolean result = lineDailyBatchJobService.startPendingBatch(pending, "manager-1");

        assertTrue(result);
        verify(lineDailyBatchJobMapper).updateStatusFromPending(
                pending.getId(),
                LineDailyBatchStatus.RUNNING,
                "manager-1"
        );
    }

    @Test
    @DisplayName("PENDING이 아닌 batch는 RUNNING 전환 SQL을 실행하지 않는다")
    void doesNotStartNonPendingBatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING);

        boolean result = lineDailyBatchJobService.startPendingBatch(running, "manager-1");

        assertFalse(result);
        verify(lineDailyBatchJobMapper, never()).updateStatusFromPending(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("target insert batch 완료 시 target_count와 success_count를 확정한다")
    void completesRunningTargetInsertBatch() {
        LineDailyBatchJob pendingDomainAfterStart = batchJob(LineDailyBatchStatus.PENDING);
        when(lineDailyBatchJobMapper.completeRunningTargetInsertBatch(
                pendingDomainAfterStart.getId(),
                3L
        )).thenReturn(1);

        boolean result = lineDailyBatchJobService.completeRunningTargetInsertBatch(
                pendingDomainAfterStart,
                3L
        );

        assertTrue(result);
        verify(lineDailyBatchJobMapper).completeRunningTargetInsertBatch(
                pendingDomainAfterStart.getId(),
                3L
        );
    }

    @Test
    @DisplayName("usage sync batch는 PENDING일 때만 target_count와 함께 RUNNING 전환한다")
    void startsPendingUsageSyncBatchWithTargetCount() {
        LineDailyBatchJob pending = batchJob(LineDailyBatchStatus.PENDING);
        when(lineDailyBatchJobMapper.startPendingUsageSyncBatchWithTargetCount(
                pending.getId(),
                3L,
                "manager-1"
        )).thenReturn(1);

        boolean result = lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                pending,
                3L,
                "manager-1"
        );

        assertTrue(result);
        verify(lineDailyBatchJobMapper).startPendingUsageSyncBatchWithTargetCount(
                pending.getId(),
                3L,
                "manager-1"
        );
    }

    @Test
    @DisplayName("PENDING이 아닌 usage sync batch는 RUNNING 전환 SQL을 실행하지 않는다")
    void doesNotStartNonPendingUsageSyncBatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING);

        boolean result = lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                running,
                3L,
                "manager-1"
        );

        assertFalse(result);
        verify(lineDailyBatchJobMapper, never()).startPendingUsageSyncBatchWithTargetCount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("rerun usage sync batch는 RUNNING 상태와 지정 target_count로 새 row를 생성한다")
    void createsRunningRerunUsageSyncBatch() {
        LineDailyBatchJob result = lineDailyBatchJobService.createRunningRerunUsageSyncBatch(USAGE_DATE, 5L);

        ArgumentCaptor<LineDailyBatchJob> captor = ArgumentCaptor.forClass(LineDailyBatchJob.class);
        verify(lineDailyBatchJobMapper).insertRunningRerunUsageSyncBatch(captor.capture());
        LineDailyBatchJob inserted = captor.getValue();

        assertSame(inserted, result);
        assertEquals(BatchName.LINE_DAILY_USAGE_SYNC_BATCH, inserted.getBatchName());
        assertEquals(USAGE_DATE, inserted.getUsageDate());
        assertEquals(LineDailyBatchStatus.RUNNING, inserted.getStatus());
        assertEquals(5L, inserted.getTargetCount());
        assertEquals(0L, inserted.getSuccessCount());
        assertEquals(0L, inserted.getFailedCount());
        assertEquals(0L, inserted.getSkippedCount());
    }

    @Test
    @DisplayName("usage sync batch 완료 CAS는 mapper affected rows 1건일 때만 성공한다")
    void completesRunningUsageSyncBatchIfCountsMatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobMapper.completeRunningUsageSyncBatchIfCountsMatch(running.getId()))
                .thenReturn(1);

        boolean result = lineDailyBatchJobService.completeRunningUsageSyncBatchIfCountsMatch(running);

        assertTrue(result);
        verify(lineDailyBatchJobMapper).completeRunningUsageSyncBatchIfCountsMatch(running.getId());
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
