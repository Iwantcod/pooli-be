package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncRerunResDto;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncRerunServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @Mock
    private LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    @Mock
    private LineDailyBatchWorkerScheduler lineDailyBatchWorkerScheduler;

    @InjectMocks
    private LineDailyUsageSyncRerunService lineDailyUsageSyncRerunService;

    @Test
    @DisplayName("FAILED usage sync batch는 FAILED target만 새 RUNNING batch로 rerun한다")
    void rerunsFailedUsageSyncBatch() {
        LineDailyBatchJob previous = batchJob(7L, LineDailyBatchStatus.FAILED);
        LineDailyBatchJob rerun = batchJob(8L, LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(previous);
        when(lineDailyBatchTargetMapper.countByUsageDateAndStatus(
                USAGE_DATE,
                LineDailyBatchTargetStatus.FAILED
        )).thenReturn(3L);
        when(lineDailyBatchJobService.createRunningRerunUsageSyncBatch(USAGE_DATE, 3L)).thenReturn(rerun);

        LineDailyUsageSyncRerunResDto response = lineDailyUsageSyncRerunService.rerun(USAGE_DATE);

        assertTrue(response.isRerunAccepted());
        assertEquals(7L, response.getPreviousBatchJobId());
        assertEquals(8L, response.getRerunBatchJobId());
        assertEquals(USAGE_DATE, response.getUsageDate());
        assertEquals(LineDailyBatchStatus.FAILED, response.getPreviousStatus());
        assertEquals(3L, response.getTargetCount());
        verify(lineDailyBatchTargetMapper).markFailedTargetsRetryableByUsageDate(USAGE_DATE);
        verify(lineDailyBatchWorkerScheduler).startForUsageDate(USAGE_DATE);
    }

    @Test
    @DisplayName("ABANDONED usage sync batch도 rerun 대상이다")
    void rerunsAbandonedUsageSyncBatch() {
        LineDailyBatchJob previous = batchJob(7L, LineDailyBatchStatus.ABANDONED);
        LineDailyBatchJob rerun = batchJob(8L, LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(previous);
        when(lineDailyBatchTargetMapper.countByUsageDateAndStatus(
                USAGE_DATE,
                LineDailyBatchTargetStatus.FAILED
        )).thenReturn(2L);
        when(lineDailyBatchJobService.createRunningRerunUsageSyncBatch(USAGE_DATE, 2L)).thenReturn(rerun);

        LineDailyUsageSyncRerunResDto response = lineDailyUsageSyncRerunService.rerun(USAGE_DATE);

        assertTrue(response.isRerunAccepted());
        assertEquals(LineDailyBatchStatus.ABANDONED, response.getPreviousStatus());
        assertEquals(2L, response.getTargetCount());
        verify(lineDailyBatchWorkerScheduler).startForUsageDate(USAGE_DATE);
    }

    @Test
    @DisplayName("usage sync batch가 없으면 rerun 요청을 거부한다")
    void rejectsWhenBatchIsAbsent() {
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(null);

        LineDailyUsageSyncRerunResDto response = lineDailyUsageSyncRerunService.rerun(USAGE_DATE);

        assertFalse(response.isRerunAccepted());
        assertNull(response.getPreviousBatchJobId());
        assertNull(response.getRerunBatchJobId());
        assertEquals(USAGE_DATE, response.getUsageDate());
        assertNull(response.getPreviousStatus());
        assertEquals(0L, response.getTargetCount());
        verify(lineDailyBatchJobService, never()).createRunningRerunUsageSyncBatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verify(lineDailyBatchWorkerScheduler, never()).startForUsageDate(USAGE_DATE);
    }

    @ParameterizedTest
    @EnumSource(
            value = LineDailyBatchStatus.class,
            names = {"PENDING", "RUNNING", "COMPLETED"}
    )
    @DisplayName("FAILED 또는 ABANDONED가 아닌 usage sync batch는 rerun 요청을 거부한다")
    void rejectsNonRerunnableBatch(LineDailyBatchStatus status) {
        LineDailyBatchJob previous = batchJob(7L, status);
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(previous);

        LineDailyUsageSyncRerunResDto response = lineDailyUsageSyncRerunService.rerun(USAGE_DATE);

        assertFalse(response.isRerunAccepted());
        assertEquals(7L, response.getPreviousBatchJobId());
        assertNull(response.getRerunBatchJobId());
        assertEquals(status, response.getPreviousStatus());
        verify(lineDailyBatchTargetMapper, never()).countByUsageDateAndStatus(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(lineDailyBatchWorkerScheduler, never()).startForUsageDate(USAGE_DATE);
    }

    private LineDailyBatchJob batchJob(Long id, LineDailyBatchStatus status) {
        return LineDailyBatchJob.builder()
                .id(id)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(status)
                .build();
    }
}
