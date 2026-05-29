package com.pooli.traffic.service.restore;

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
    private TrafficRestoreExecutionService executionService;

    @Mock
    private TrafficRestoreStartDateResolver startDateResolver;

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
    @DisplayName("복구 시작은 flag 활성화, 대기, 복구 phase 실행, flag 비활성화 순서로 진행된다")
    void startsRestoreInRequiredOrder() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));

        var response = service.start(request);

        var inOrder = org.mockito.Mockito.inOrder(policyFlagService, waitService, executionService);
        inOrder.verify(policyFlagService).activateRestoreFlag();
        inOrder.verify(waitService).waitWorstProcessingTimePlusBuffer();
        inOrder.verify(executionService).execute(
                org.mockito.Mockito.eq(LocalDate.of(2026, 5, 29)),
                org.mockito.Mockito.eq(LocalDate.of(2026, 5, 27)),
                org.mockito.Mockito.eq(java.util.List.of(java.time.YearMonth.of(2026, 5)))
        );
        verify(policyFlagService).deactivateRestoreFlag();
        org.assertj.core.api.Assertions.assertThat(response.accepted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(response.nextPhase()).isEqualTo("RESTORE_COMPLETED");
        org.assertj.core.api.Assertions.assertThat(response.failureDate()).isEqualTo(LocalDate.of(2026, 5, 29));
        org.assertj.core.api.Assertions.assertThat(response.restoreStartDate()).isEqualTo(LocalDate.of(2026, 5, 27));
    }

    @Test
    @DisplayName("복구 시작일이 장애일보다 늦으면 복구 대상 없음 응답을 반환하고 flag를 활성화하지 않는다")
    void returnsNoTargetWhenRestoreStartDateIsAfterFailureDate() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 30));

        var response = service.start(request);

        org.assertj.core.api.Assertions.assertThat(response.accepted()).isFalse();
        org.assertj.core.api.Assertions.assertThat(response.nextPhase()).isEqualTo("NO_RESTORE_TARGET");
        org.assertj.core.api.Assertions.assertThat(response.failureDate()).isEqualTo(LocalDate.of(2026, 5, 29));
        org.assertj.core.api.Assertions.assertThat(response.restoreStartDate()).isEqualTo(LocalDate.of(2026, 5, 30));
        verify(policyFlagService, never()).activateRestoreFlag();
        verify(executionService, never())
                .execute(
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any(),
                        org.mockito.Mockito.any()
                );
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
