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
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncResumeResDto;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncResumeServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @Mock
    private LineDailyBatchWorkerScheduler lineDailyBatchWorkerScheduler;

    @InjectMocks
    private LineDailyUsageSyncResumeService lineDailyUsageSyncResumeService;

    @Test
    @DisplayName("RUNNING usage sync batch이면 worker scheduler에 재개 요청을 전달한다")
    void resumesRunningUsageSyncBatch() {
        LineDailyBatchJob running = batchJob(LineDailyBatchStatus.RUNNING);
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(running);

        LineDailyUsageSyncResumeResDto response = lineDailyUsageSyncResumeService.resume(USAGE_DATE);

        assertTrue(response.isResumeAccepted());
        assertEquals(2L, response.getBatchJobId());
        assertEquals(USAGE_DATE, response.getUsageDate());
        assertEquals(LineDailyBatchStatus.RUNNING, response.getStatus());
        verify(lineDailyBatchWorkerScheduler).startForUsageDate(USAGE_DATE);
    }

    @Test
    @DisplayName("usage sync batch가 없으면 재개 요청을 거부하고 scheduler를 호출하지 않는다")
    void rejectsWhenUsageSyncBatchIsAbsent() {
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(null);

        LineDailyUsageSyncResumeResDto response = lineDailyUsageSyncResumeService.resume(USAGE_DATE);

        assertFalse(response.isResumeAccepted());
        assertNull(response.getBatchJobId());
        assertEquals(USAGE_DATE, response.getUsageDate());
        assertNull(response.getStatus());
        verify(lineDailyBatchWorkerScheduler, never()).startForUsageDate(USAGE_DATE);
    }

    @ParameterizedTest
    @EnumSource(
            value = LineDailyBatchStatus.class,
            names = {"PENDING", "COMPLETED", "FAILED", "ABANDONED"}
    )
    @DisplayName("RUNNING이 아닌 usage sync batch는 재개 요청을 거부한다")
    void rejectsNonRunningUsageSyncBatch(LineDailyBatchStatus status) {
        LineDailyBatchJob batchJob = batchJob(status);
        when(lineDailyBatchJobService.findLatestUsageSyncBatch(USAGE_DATE)).thenReturn(batchJob);

        LineDailyUsageSyncResumeResDto response = lineDailyUsageSyncResumeService.resume(USAGE_DATE);

        assertFalse(response.isResumeAccepted());
        assertEquals(2L, response.getBatchJobId());
        assertEquals(USAGE_DATE, response.getUsageDate());
        assertEquals(status, response.getStatus());
        verify(lineDailyBatchWorkerScheduler, never()).startForUsageDate(USAGE_DATE);
    }

    private LineDailyBatchJob batchJob(LineDailyBatchStatus status) {
        return LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(status)
                .build();
    }
}
