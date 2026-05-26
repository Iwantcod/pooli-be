package com.pooli.traffic.service.batch;

import static org.mockito.ArgumentMatchers.anyString;
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

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncWorkerServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;

    @InjectMocks
    private LineDailyUsageSyncWorkerService lineDailyUsageSyncWorkerService;

    @Test
    @DisplayName("worker loop 시작 시 같은 usage_date의 target row 한 chunk를 선점한다")
    void claimsTargetChunkForUsageDateWhenWorkerStarts() {
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of());

        lineDailyUsageSyncWorkerService.run(batchJob);

        verify(lineDailyBatchTargetClaimService).claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        );
    }
}
