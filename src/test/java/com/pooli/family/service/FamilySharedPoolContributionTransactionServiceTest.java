package com.pooli.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.monitoring.metrics.TrafficSharedPoolContributionCleanupMetrics;
import com.pooli.traffic.domain.TrafficSharedPoolContributionLuaResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockHandle;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockPair;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class FamilySharedPoolContributionTransactionServiceTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private FamilySharedPoolMapper sharedPoolMapper;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private TrafficSharedPoolContributionCleanupMetrics cleanupMetrics;

    private FamilySharedPoolContributionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new FamilySharedPoolContributionTransactionService(
                redisOutboxRecordService,
                sharedPoolMapper,
                trafficLuaScriptInfraService,
                trafficRedisKeyFactory,
                trafficRedisFailureClassifier,
                cleanupMetrics
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("outbox 생성은 record service에 위임한다")
    void createOutboxDelegatesToRecordService() {
        SharedPoolContributionOutboxPayload payload = payload();
        when(redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(payload, "trace-create"))
                .thenReturn(OutboxCreateResult.created(101L));

        OutboxCreateResult result = service.createOutbox(payload, "trace-create");

        assertThat(result.created()).isTrue();
        assertThat(result.outboxId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("요청 경로는 DB 성공 commit 이후 cleanup을 실행한다")
    void processRegistersCleanupAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        SharedPoolContributionOutboxPayload payload = payload();
        stubKeys("trace-contribution-001");
        HydrateLockPair lockPair = lockPair();
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.of(lockPair));
        when(trafficLuaScriptInfraService.executeSharedPoolContributionApply(
                "metadata:trace-contribution-001",
                "indiv:11:202605",
                "shared:22:202605",
                "trace-contribution-001",
                100L,
                false
        )).thenReturn(appliedResult());
        when(sharedPoolMapper.updateLineRemainingData(11L, 100L)).thenReturn(1);
        when(sharedPoolMapper.updateFamilyPoolData(22L, 100L)).thenReturn(1);

        FamilySharedPoolContributionTransactionService.ProcessingResult result =
                service.processContribution(101L, payload, "trace-contribution-001", false);

        assertThat(result).isEqualTo(FamilySharedPoolContributionTransactionService.ProcessingResult.SUCCESS);
        verify(redisOutboxRecordService).markSuccess(101L);
        verify(sharedPoolMapper).insertContribution(22L, 11L, 100L, LocalDate.of(2026, 5, 16));
        verify(trafficLuaScriptInfraService, never()).executeSharedPoolContributionCleanup(any(), any());

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-contribution-001", lockPair);
    }

    @Test
    @DisplayName("DB rollback 완료 시 cleanup은 실행하지 않고 lock만 반납한다")
    void processReleasesLockAfterRollbackWithoutCleanup() {
        TransactionSynchronizationManager.initSynchronization();
        SharedPoolContributionOutboxPayload payload = payload();
        stubKeys("trace-rollback");
        HydrateLockPair lockPair = lockPair();
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.of(lockPair));
        when(trafficLuaScriptInfraService.executeSharedPoolContributionApply(
                "metadata:trace-rollback",
                "indiv:11:202605",
                "shared:22:202605",
                "trace-rollback",
                100L,
                false
        )).thenReturn(appliedResult());
        when(sharedPoolMapper.updateLineRemainingData(11L, 100L)).thenReturn(1);
        when(sharedPoolMapper.updateFamilyPoolData(22L, 100L)).thenReturn(1);

        service.processContribution(101L, payload, "trace-rollback", false);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(trafficLuaScriptInfraService, never()).executeSharedPoolContributionCleanup(any(), any());
        verify(trafficLuaScriptInfraService).releaseHydrateLocks(lockPair);
    }

    @Test
    @DisplayName("cleanup 실패는 성공 처리를 되돌리지 않고 전용 metric만 증가시킨다")
    void cleanupFailureOnlyRecordsMetric() {
        TransactionSynchronizationManager.initSynchronization();
        SharedPoolContributionOutboxPayload payload = payload();
        stubKeys("trace-cleanup-fail");
        HydrateLockPair lockPair = lockPair();
        RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.of(lockPair));
        when(trafficLuaScriptInfraService.executeSharedPoolContributionApply(
                "metadata:trace-cleanup-fail",
                "indiv:11:202605",
                "shared:22:202605",
                "trace-cleanup-fail",
                100L,
                false
        )).thenReturn(appliedResult());
        when(sharedPoolMapper.updateLineRemainingData(11L, 100L)).thenReturn(1);
        when(sharedPoolMapper.updateFamilyPoolData(22L, 100L)).thenReturn(1);
        when(trafficLuaScriptInfraService.executeSharedPoolContributionCleanup("metadata:trace-cleanup-fail", lockPair))
                .thenThrow(cleanupFailure);
        when(trafficRedisFailureClassifier.isTimeoutFailure(cleanupFailure)).thenReturn(false);
        when(trafficRedisFailureClassifier.isConnectionFailure(cleanupFailure)).thenReturn(false);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(cleanupFailure)).thenReturn(false);

        FamilySharedPoolContributionTransactionService.ProcessingResult result =
                service.processContribution(101L, payload, "trace-cleanup-fail", false);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(result).isEqualTo(FamilySharedPoolContributionTransactionService.ProcessingResult.SUCCESS);
        verify(redisOutboxRecordService).markSuccess(101L);
        verify(cleanupMetrics).incrementFailure("request", "non_retryable");
    }

    @Test
    @DisplayName("스케줄러 복구에서 metadata가 없으면 CANCELED로 닫고 commit 이후 cleanup한다")
    void recoverCancelsWhenMetadataMissing() {
        TransactionSynchronizationManager.initSynchronization();
        SharedPoolContributionOutboxPayload payload = payload();
        stubKeys("trace-recover");
        HydrateLockPair lockPair = lockPair();
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.of(lockPair));
        when(trafficLuaScriptInfraService.executeSharedPoolContributionRecover(
                "metadata:trace-recover",
                "indiv:11:202605",
                "shared:22:202605",
                false
        )).thenReturn(TrafficSharedPoolContributionLuaResult.builder()
                .status("METADATA_MISSING")
                .individualApplied(0L)
                .sharedApplied(0L)
                .build());

        FamilySharedPoolContributionTransactionService.ProcessingResult result =
                service.processContribution(201L, payload, "trace-recover", true);

        assertThat(result).isEqualTo(FamilySharedPoolContributionTransactionService.ProcessingResult.CANCELED);
        verify(redisOutboxRecordService).markCanceled(201L);
        verify(sharedPoolMapper, never()).updateFamilyPoolData(anyLong(), anyLong());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-recover", lockPair);
    }

    @Test
    @DisplayName("스케줄러 복구는 Redis 보정 후 MySQL source를 반영하고 SUCCESS로 닫는다")
    void recoverAppliesMysqlAfterRedisRecovery() {
        SharedPoolContributionOutboxPayload payload = payload();
        stubKeys("trace-recover");
        HydrateLockPair lockPair = lockPair();
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.of(lockPair));
        when(trafficLuaScriptInfraService.executeSharedPoolContributionRecover(
                "metadata:trace-recover",
                "indiv:11:202605",
                "shared:22:202605",
                false
        )).thenReturn(appliedResult());
        when(sharedPoolMapper.updateLineRemainingData(11L, 100L)).thenReturn(1);
        when(sharedPoolMapper.updateFamilyPoolData(22L, 100L)).thenReturn(1);

        FamilySharedPoolContributionTransactionService.ProcessingResult result =
                service.processContribution(201L, payload, "trace-recover", true);

        assertThat(result).isEqualTo(FamilySharedPoolContributionTransactionService.ProcessingResult.SUCCESS);
        verify(sharedPoolMapper).insertContribution(22L, 11L, 100L, LocalDate.of(2026, 5, 16));
        verify(redisOutboxRecordService).markSuccess(201L);
        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-recover", lockPair);
    }

    private void stubKeys(String traceId) {
        when(trafficRedisKeyFactory.sharedPoolContributionMetadataKey(traceId))
                .thenReturn("metadata:" + traceId);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, YearMonth.of(2026, 5)))
                .thenReturn("indiv:11:202605");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, YearMonth.of(2026, 5)))
                .thenReturn("shared:22:202605");
        when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("indiv-lock:11");
        when(trafficRedisKeyFactory.sharedHydrateLockKey(22L)).thenReturn("shared-lock:22");
    }

    private HydrateLockPair lockPair() {
        return new HydrateLockPair(
                new HydrateLockHandle("indiv-lock:11", "indiv-owner"),
                new HydrateLockHandle("shared-lock:22", "shared-owner")
        );
    }

    private TrafficSharedPoolContributionLuaResult appliedResult() {
        return TrafficSharedPoolContributionLuaResult.builder()
                .status("APPLIED")
                .individualApplied(1L)
                .sharedApplied(1L)
                .build();
    }

    private SharedPoolContributionOutboxPayload payload() {
        return SharedPoolContributionOutboxPayload.builder()
                .lineId(11L)
                .familyId(22L)
                .amount(100L)
                .individualUnlimited(false)
                .targetMonth("202605")
                .usageDate("2026-05-16")
                .build();
    }
}
