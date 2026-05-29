package com.pooli.traffic.service.restore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreBatchMetadataServiceTest {

    @Mock
    private TrafficRestoreHydrateTargetMapper hydrateTargetMapper;

    @Mock
    private LineDailyBatchJobMapper lineDailyBatchJobMapper;

    @InjectMocks
    private TrafficRestoreBatchMetadataService service;

    @Test
    @DisplayName("restore phase는 모든 대상이 DONE일 때만 완료된다")
    void completesOnlyWhenAllTargetsAreDone() {
        LineDailyBatchJob batchJob = restoreBatchJob(BatchName.RESTORE_P0_REDIS_HYDRATE);
        when(hydrateTargetMapper.countFailedTargets(BatchName.RESTORE_P0_REDIS_HYDRATE.name())).thenReturn(0L);
        when(hydrateTargetMapper.countNotDoneTargets(BatchName.RESTORE_P0_REDIS_HYDRATE.name())).thenReturn(0L);
        when(lineDailyBatchJobMapper.completeRunningRestorePhaseBatch(
                batchJob.getId(),
                BatchName.RESTORE_P0_REDIS_HYDRATE
        )).thenReturn(1);

        boolean completed = service.completePhaseIfAllTargetsDone(batchJob);

        assertTrue(completed);
    }

    @Test
    @DisplayName("FAILED target이 남아 있으면 restore phase를 완료하지 않는다")
    void doesNotCompleteWhenFailedTargetExists() {
        LineDailyBatchJob batchJob = restoreBatchJob(BatchName.RESTORE_P0_REDIS_HYDRATE);
        when(hydrateTargetMapper.countFailedTargets(BatchName.RESTORE_P0_REDIS_HYDRATE.name())).thenReturn(1L);

        boolean completed = service.completePhaseIfAllTargetsDone(batchJob);

        assertFalse(completed);
        verify(lineDailyBatchJobMapper, never()).completeRunningRestorePhaseBatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private LineDailyBatchJob restoreBatchJob(BatchName batchName) {
        return LineDailyBatchJob.builder()
                .id(10L)
                .batchName(batchName)
                .usageDate(LocalDate.of(2026, 5, 29))
                .status(LineDailyBatchStatus.RUNNING)
                .build();
    }
}
