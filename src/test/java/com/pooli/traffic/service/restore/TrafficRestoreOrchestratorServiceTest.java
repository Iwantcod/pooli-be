package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreOrchestratorServiceTest {

    private static final String RESTORE_LOCK_KEY = "pooli:traffic:restore:manager-lock";

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

    @Mock
    private Executor taskExecutor;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    private TrafficRestoreOrchestratorService service;

    @BeforeEach
    void setUp() {
        service = new TrafficRestoreOrchestratorService(
                policyFlagService,
                policyBootstrapService,
                waitService,
                executionService,
                startDateResolver,
                batchJobMapper,
                hydrateTargetMapper,
                dailyAppTargetMapper,
                doneLogMapper,
                taskExecutor,
                cacheStringRedisTemplate,
                trafficRedisKeyFactory,
                trafficLuaScriptInfraService
        );
    }

    @Test
    @DisplayName("복구 시작은 Redis lock 획득 후 background worker를 제출하고 접수 응답을 반환한다")
    void startSubmitsBackgroundWorkerAndReturnsAcceptedResponse() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));
        givenRestoreLockAcquired();

        var response = service.start(request);

        verify(taskExecutor).execute(any(Runnable.class));
        verify(policyFlagService, never()).activateRestoreFlag();
        verify(executionService, never()).execute(any(), any(), any());
        assertThat(response.accepted()).isTrue();
        assertThat(response.nextPhase()).isEqualTo("RESTORE_ACCEPTED");
        assertThat(response.failureDate()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(response.restoreStartDate()).isEqualTo(LocalDate.of(2026, 5, 27));
    }

    @Test
    @DisplayName("복구 시작은 Redis lock을 획득하지 못하면 중복 요청으로 거절한다")
    void startRejectsDuplicateWhenRestoreLockAlreadyHeld() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));
        givenRestoreLockNotAcquired();

        var response = service.start(request);

        assertThat(response.accepted()).isFalse();
        assertThat(response.nextPhase()).isEqualTo("RESTORE_ALREADY_RUNNING");
        verify(taskExecutor, never()).execute(any(Runnable.class));
        verify(policyFlagService, never()).activateRestoreFlag();
        verify(executionService, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("background worker는 flag 활성화, policy hydrate, 대기, phase 실행 후 flag와 lock을 정리한다")
    void backgroundWorkerRunsRestoreAndAlwaysCleansUpOnSuccess() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));
        givenRestoreLockAcquired();
        when(trafficLuaScriptInfraService.executeLockRelease(eq(RESTORE_LOCK_KEY), anyString()))
                .thenReturn(true);
        ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);
        doNothing().when(taskExecutor).execute(workerCaptor.capture());

        service.start(request);
        workerCaptor.getValue().run();

        var inOrder = inOrder(
                policyFlagService,
                policyBootstrapService,
                waitService,
                executionService,
                trafficLuaScriptInfraService
        );
        inOrder.verify(policyFlagService).activateRestoreFlag();
        inOrder.verify(policyBootstrapService).hydrateOnDemand();
        inOrder.verify(waitService).waitWorstProcessingTimePlusBuffer();
        inOrder.verify(executionService).execute(
                eq(LocalDate.of(2026, 5, 29)),
                eq(LocalDate.of(2026, 5, 27)),
                eq(java.util.List.of(java.time.YearMonth.of(2026, 5)))
        );
        inOrder.verify(policyFlagService).deactivateRestoreFlag();
        inOrder.verify(trafficLuaScriptInfraService).executeLockRelease(eq(RESTORE_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("background worker는 phase 실행이 실패해도 flag와 lock을 정리한다")
    void backgroundWorkerCleansUpWhenExecutionFails() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));
        givenRestoreLockAcquired();
        when(trafficLuaScriptInfraService.executeLockRelease(eq(RESTORE_LOCK_KEY), anyString()))
                .thenReturn(true);
        doThrow(new IllegalStateException("restore failed"))
                .when(executionService)
                .execute(any(), any(), any());
        ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);
        doNothing().when(taskExecutor).execute(workerCaptor.capture());

        service.start(request);

        assertThatThrownBy(() -> workerCaptor.getValue().run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("restore failed");
        verify(policyFlagService).deactivateRestoreFlag();
        verify(trafficLuaScriptInfraService).executeLockRelease(eq(RESTORE_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("background worker는 flag 해제에 실패해도 Redis lock 해제를 시도한다")
    void backgroundWorkerReleasesLockWhenFlagDeactivationFails() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        when(startDateResolver.resolve(LocalDate.of(2026, 5, 29)))
                .thenReturn(LocalDate.of(2026, 5, 27));
        givenRestoreLockAcquired();
        doThrow(new IllegalStateException("flag failed"))
                .when(policyFlagService)
                .deactivateRestoreFlag();
        when(trafficLuaScriptInfraService.executeLockRelease(eq(RESTORE_LOCK_KEY), anyString()))
                .thenReturn(true);
        ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);
        doNothing().when(taskExecutor).execute(workerCaptor.capture());

        service.start(request);
        workerCaptor.getValue().run();

        verify(policyFlagService).deactivateRestoreFlag();
        verify(trafficLuaScriptInfraService).executeLockRelease(eq(RESTORE_LOCK_KEY), anyString());
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

        assertThat(response.accepted()).isFalse();
        assertThat(response.nextPhase()).isEqualTo("NO_RESTORE_TARGET");
        assertThat(response.failureDate()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(response.restoreStartDate()).isEqualTo(LocalDate.of(2026, 5, 30));
        verify(policyFlagService, never()).activateRestoreFlag();
        verify(executionService, never())
                .execute(
                        any(),
                        any(),
                        any()
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
        when(batchJobMapper.restartRestorePhaseBatch(
                10L,
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                "admin-resume"
        )).thenReturn(1);
        givenRestoreLockAcquired();

        service.resume(anchorDate);

        verify(doneLogMapper)
                .resetFailedRestoreLogsToRetryable(anchorDate.plusDays(1).atStartOfDay());
        verify(batchJobMapper).restartRestorePhaseBatch(
                10L,
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                "admin-resume"
        );
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("복구 재개는 Redis lock을 획득하지 못하면 metadata를 재시작하지 않고 거절한다")
    void resumeRejectsWhenRestoreLockAlreadyHeld() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(10L)
                .batchName(BatchName.RESTORE_P2_DONE_LOG_REPLAY)
                .usageDate(anchorDate)
                .status(LineDailyBatchStatus.FAILED)
                .build();
        when(batchJobMapper.selectLatestByBatchNameAndUsageDate(
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                anchorDate
        )).thenReturn(batchJob);
        givenRestoreLockNotAcquired();

        var response = service.resume(anchorDate);

        assertThat(response.resumeAccepted()).isFalse();
        assertThat(response.currentStatus()).isEqualTo("RESTORE_ALREADY_RUNNING");
        verify(batchJobMapper, never()).restartRestorePhaseBatch(any(), any(), any());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    private void givenRestoreLockAcquired() {
        when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn(RESTORE_LOCK_KEY);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RESTORE_LOCK_KEY),
                anyString(),
                eq(Duration.ofMillis(TrafficRestoreOrchestratorService.RESTORE_MANAGER_LOCK_TTL_MS))
        )).thenReturn(true);
    }

    private void givenRestoreLockNotAcquired() {
        when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn(RESTORE_LOCK_KEY);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RESTORE_LOCK_KEY),
                anyString(),
                eq(Duration.ofMillis(TrafficRestoreOrchestratorService.RESTORE_MANAGER_LOCK_TTL_MS))
        )).thenReturn(false);
    }
}
