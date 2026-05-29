package com.pooli.traffic.service.restore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;

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
import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreOrchestratorServiceTest {

    @Mock
    private TrafficRestorePolicyFlagService policyFlagService;

    @Mock
    private TrafficPolicyBootstrapService policyBootstrapService;

    @Mock
    private TrafficRestoreWaitService waitService;

    @Mock
    private TrafficRestorePhase0TargetInsertService phase0TargetInsertService;

    @Mock
    private LineDailyBatchJobMapper batchJobMapper;

    @Mock
    private TrafficRestoreHydrateTargetMapper hydrateTargetMapper;

    @Mock
    private TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;

    @Mock
    private TrafficDeductDoneLogMapper doneLogMapper;

    @InjectMocks
    private TrafficRestoreOrchestratorService service;

    @Test
    @DisplayName("복구 시작은 flag 활성화, 대기, phase 0 시작 순서로 진행된다")
    void startsRestoreInRequiredOrder() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 27)
        );

        service.start(request);

        var inOrder = inOrder(policyFlagService, waitService, phase0TargetInsertService);
        inOrder.verify(policyFlagService).activateRestoreFlag();
        inOrder.verify(waitService).waitWorstProcessingTimePlusBuffer();
        inOrder.verify(phase0TargetInsertService).insertTargets(any(), any(), any(), any());
    }

    @Test
    @DisplayName("복구 재개는 phase 2 FAILED done log를 RETRYABLE로 되돌리고 metadata를 RUNNING으로 연다")
    void resumesFailedPhase2Batch() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(10L)
                .batchName(BatchName.RESTORE_P2_DONE_LOG_REPLAY)
                .usageDate(anchorDate)
                .status(LineDailyBatchStatus.FAILED)
                .build();
        org.mockito.Mockito.when(batchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                anchorDate
        )).thenReturn(batchJob);

        service.resume(anchorDate);

        org.mockito.Mockito.verify(doneLogMapper)
                .resetFailedRestoreLogsToRetryable(anchorDate.plusDays(1).atStartOfDay());
        org.mockito.Mockito.verify(batchJobMapper).restartRestorePhaseBatch(
                10L,
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                "admin-resume"
        );
    }
}
