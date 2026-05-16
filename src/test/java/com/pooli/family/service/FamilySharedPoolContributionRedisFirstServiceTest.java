package com.pooli.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.traffic.domain.TrafficSharedPoolContributionLuaResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.OutboxStatus;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockHandle;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockPair;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class FamilySharedPoolContributionRedisFirstServiceTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private FamilySharedPoolMapper sharedPoolMapper;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private FamilySharedPoolContributionRedisFirstService service;

    @BeforeEach
    void setUp() {
        service = new FamilySharedPoolContributionRedisFirstService(
                redisOutboxRecordService,
                sharedPoolMapper,
                trafficLuaScriptInfraService,
                trafficRedisKeyFactory,
                trafficRedisRuntimePolicy,
                trafficRedisFailureClassifier,
                transactionManager
        );
        when(transactionManager.getTransaction(any(DefaultTransactionDefinition.class)))
                .thenReturn(transactionStatus);
    }

    @Test
    @DisplayName("요청 경로는 outbox 생성 후 Redis Lua, MySQL source, SUCCESS, cleanup 순서로 처리한다")
    void submitAppliesRedisFirstContribution() {
        MDC.put("traceId", "trace-contribution-001");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(any(), eq("trace-contribution-001")))
                .thenReturn(OutboxCreateResult.created(101L));
        stubKeys();
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

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isTrue();
        verify(redisOutboxRecordService).markSuccess(101L);
        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-contribution-001", lockPair);
        verify(sharedPoolMapper).insertContribution(eq(22L), eq(11L), eq(100L), any(LocalDate.class));
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("기존 접수 outbox가 있으면 Redis와 MySQL 처리를 반복하지 않는다")
    void submitDuplicateSkipsProcessing() {
        MDC.put("traceId", "trace-duplicate");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(any(), eq("trace-duplicate")))
                .thenReturn(OutboxCreateResult.duplicate());

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isFalse();
        verify(trafficLuaScriptInfraService, never()).tryAcquireHydrateLocks(any(), any());
        verify(sharedPoolMapper, never()).updateFamilyPoolData(anyLong(), anyLong());
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("즉시 재시도 3회가 모두 lock 획득 실패면 retry_count를 3까지 기록하고 복구 대상으로 남긴다")
    void submitLeavesOutboxWhenImmediateRetriesExhausted() {
        MDC.put("traceId", "trace-lock-busy");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(any(), eq("trace-lock-busy")))
                .thenReturn(OutboxCreateResult.created(102L));
        stubKeys();
        when(trafficLuaScriptInfraService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22"))
                .thenReturn(Optional.empty());

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isFalse();
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 1);
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 2);
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 3);
        verify(sharedPoolMapper, never()).updateLineRemainingData(anyLong(), anyLong());
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("스케줄러 복구에서 metadata가 없으면 CANCELED로 종료한다")
    void recoverCancelsWhenMetadataMissing() {
        SharedPoolContributionOutboxPayload payload = payload();
        RedisOutboxRecord record = record();
        when(redisOutboxRecordService.readPayload(record, SharedPoolContributionOutboxPayload.class))
                .thenReturn(payload);
        stubKeys();
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

        OutboxRetryResult result = service.recover(record);

        assertThat(result).isEqualTo(OutboxRetryResult.SUCCESS);
        verify(redisOutboxRecordService).markCanceled(201L);
        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-recover", lockPair);
        verify(sharedPoolMapper, never()).updateFamilyPoolData(anyLong(), anyLong());
    }

    @Test
    @DisplayName("스케줄러 복구는 metadata 기준 Redis 보정 후 MySQL source를 반영하고 SUCCESS로 완료한다")
    void recoverAppliesMysqlAfterRedisRecovery() {
        SharedPoolContributionOutboxPayload payload = payload();
        RedisOutboxRecord record = record();
        when(redisOutboxRecordService.readPayload(record, SharedPoolContributionOutboxPayload.class))
                .thenReturn(payload);
        stubKeys();
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

        OutboxRetryResult result = service.recover(record);

        assertThat(result).isEqualTo(OutboxRetryResult.SUCCESS);
        verify(sharedPoolMapper).insertContribution(22L, 11L, 100L, LocalDate.of(2026, 5, 16));
        verify(redisOutboxRecordService).markSuccess(201L);
        verify(trafficLuaScriptInfraService).executeSharedPoolContributionCleanup("metadata:trace-recover", lockPair);
    }

    private void stubKeys() {
        when(trafficRedisKeyFactory.sharedPoolContributionMetadataKey(any())).thenAnswer(invocation ->
                "metadata:" + invocation.getArgument(0)
        );
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

    private RedisOutboxRecord record() {
        return RedisOutboxRecord.builder()
                .id(201L)
                .eventType(OutboxEventType.SHARED_POOL_CONTRIBUTION)
                .traceId("trace-recover")
                .status(OutboxStatus.PROCESSING)
                .retryCount(1)
                .build();
    }
}
