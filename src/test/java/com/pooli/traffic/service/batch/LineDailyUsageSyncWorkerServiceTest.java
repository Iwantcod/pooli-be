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
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncWorkerServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;

    @Mock
    private LineDailyUsageRedisReader lineDailyUsageRedisReader;

    @Mock
    private LineDailyUsageSyncPersistenceService lineDailyUsageSyncPersistenceService;

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

    @Test
    @DisplayName("선점한 target row별 Redis 사용량 snapshot을 조회한다")
    void readsRedisUsageSnapshotForClaimedTargets() {
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        LineDailyBatchTarget target = LineDailyBatchTarget.builder()
                .id(10L)
                .usageDate(USAGE_DATE)
                .lineId(11L)
                .build();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(target));
        when(lineDailyUsageRedisReader.read(target))
                .thenReturn(new LineDailyUsageReadResult(null, List.of(), null));

        lineDailyUsageSyncWorkerService.run(batchJob);

        verify(lineDailyUsageRedisReader).read(target);
        verify(lineDailyUsageSyncPersistenceService).persistUsageAndCompleteTarget(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(target),
                org.mockito.ArgumentMatchers.any(LineDailyUsageReadResult.class),
                anyString()
        );
    }
}
