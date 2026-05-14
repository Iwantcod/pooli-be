package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class TrafficHydrateServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrafficDeductLuaExecutor trafficDeductLuaExecutor;

    @Mock
    private TrafficRefillSourceMapper trafficRefillSourceMapper;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;

    @Mock
    private TrafficPolicyBootstrapService trafficPolicyBootstrapService;

    @Mock
    private TrafficHydrateMetrics trafficHydrateMetrics;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    private TrafficHydrateService service;

    @BeforeEach
    void setUp() {
        TrafficBalanceSnapshotHydrateService trafficBalanceSnapshotHydrateService =
                new TrafficBalanceSnapshotHydrateService(
                        trafficRefillSourceMapper,
                        trafficRedisKeyFactory,
                        trafficRedisRuntimePolicy,
                        trafficRemainingBalanceCacheService,
                        trafficLuaScriptInfraService,
                        cacheStringRedisTemplate
                );
        service = new TrafficHydrateService(
                trafficDeductLuaExecutor,
                trafficRedisKeyFactory,
                trafficRedisRuntimePolicy,
                trafficPolicyBootstrapService,
                trafficHydrateMetrics,
                trafficBalanceSnapshotHydrateService
        );
    }

    @Test
    @DisplayName("HYDRATE가 반환되면 개인/공유 잔량과 QoS를 적재한 뒤 통합 Lua를 재시도한다")
    void recoverIfNeededHydratesThenRetries() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult = unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubIndividualHydrateLockAcquired();
        stubSharedHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L))
                .thenReturn(TrafficIndividualBalanceSnapshot.builder()
                        .lineId(11L)
                        .amount(-1L)
                        .qosSpeedLimit(1L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .build());
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(TrafficSharedBalanceSnapshot.builder()
                        .familyId(22L)
                        .amount(500L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .build());
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRemainingBalanceCacheService).hydrateIndividualSnapshot("individual-balance", -1L, 125L, 1_775_833_199L);
        verify(trafficRemainingBalanceCacheService).hydrateSharedSnapshot("shared-balance", 500L, 1_775_833_199L);
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.INDIVIDUAL);
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.SHARED);
    }

    @Test
    @DisplayName("HYDRATE_INDIVIDUAL이면 개인 snapshot만 적재한 뒤 통합 Lua를 재시도한다")
    void recoverIfNeededHydratesOnlyIndividualSnapshot() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_INDIVIDUAL);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubIndividualHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L))
                .thenReturn(TrafficIndividualBalanceSnapshot.builder()
                        .lineId(11L)
                        .amount(300L)
                        .qosSpeedLimit(1L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .build());
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRemainingBalanceCacheService).hydrateIndividualSnapshot("individual-balance", 300L, 125L, 1_775_833_199L);
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateSharedSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.INDIVIDUAL);
        verify(trafficHydrateMetrics, never()).incrementHydrate(TrafficPoolType.SHARED);
    }

    @Test
    @DisplayName("HYDRATE_SHARED이면 공유 snapshot만 적재한 뒤 통합 Lua를 재시도한다")
    void recoverIfNeededHydratesOnlySharedSnapshot() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_SHARED);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubSharedHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(TrafficSharedBalanceSnapshot.builder()
                        .familyId(22L)
                        .amount(500L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .build());
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateIndividualSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
        verify(trafficRemainingBalanceCacheService).hydrateSharedSnapshot("shared-balance", 500L, 1_775_833_199L);
        verify(trafficHydrateMetrics, never()).incrementHydrate(TrafficPoolType.INDIVIDUAL);
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.SHARED);
    }

    @Test
    @DisplayName("Case A: DB refresh 월이 targetMonth보다 과거면 조건부 월초 갱신 후 Redis snapshot을 적재한다")
    void recoverIfNeededRefreshesStaleIndividualMonthThenHydratesSnapshot() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_INDIVIDUAL);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubIndividualHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L))
                .thenReturn(
                        TrafficIndividualBalanceSnapshot.builder()
                                .lineId(11L)
                                .amount(100L)
                                .qosSpeedLimit(1L)
                                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 2, 1, 0, 0))
                                .build(),
                        TrafficIndividualBalanceSnapshot.builder()
                                .lineId(11L)
                                .amount(300L)
                                .qosSpeedLimit(2L)
                                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                                .build()
                );
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRefillSourceMapper).refreshIndividualBalanceIfBeforeTargetMonth(
                11L,
                LocalDateTime.of(2026, 3, 1, 0, 0)
        );
        verify(trafficRemainingBalanceCacheService).hydrateIndividualSnapshot("individual-balance", 300L, 250L, 1_775_833_199L);
    }

    @Test
    @DisplayName("Case B: DB refresh 월이 targetMonth와 같으면 DB 현재값을 Redis snapshot으로 반영한다")
    void recoverIfNeededHydratesCurrentSharedMonthWithoutDbRefresh() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_SHARED);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubSharedHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(TrafficSharedBalanceSnapshot.builder()
                        .familyId(22L)
                        .amount(500L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .build());
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRefillSourceMapper, never())
                .refreshSharedBalanceIfBeforeTargetMonth(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(trafficRemainingBalanceCacheService).hydrateSharedSnapshot("shared-balance", 500L, 1_775_833_199L);
    }

    @Test
    @DisplayName("Case C: targetMonth가 DB refresh 월보다 과거면 invalid 결과를 반환하고 Redis hydrate를 수행하지 않는다")
    void recoverIfNeededReturnsInvalidWhenTargetMonthBeforeDbRefreshMonth() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_INDIVIDUAL);
        stubIndividualHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L))
                .thenReturn(TrafficIndividualBalanceSnapshot.builder()
                        .lineId(11L)
                        .amount(300L)
                        .qosSpeedLimit(1L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .build());

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.ERROR, result.getStatus());
        assertEquals("STALE_TARGET_MONTH", result.getFailureReason());
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateIndividualSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
        verify(trafficDeductLuaExecutor, never())
                .executeUnifiedWithRetry(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(trafficHydrateMetrics).incrementInvalidHydrate(TrafficPoolType.INDIVIDUAL, "STALE_TARGET_MONTH");
    }

    @Test
    @DisplayName("조건부 UPDATE 경합으로 0건 갱신되어도 재조회 결과가 targetMonth면 Redis snapshot으로 수렴한다")
    void recoverIfNeededConvergesWhenConcurrentWorkerAlreadyRefreshedMonth() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE_SHARED);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        stubSharedHydrateLockAcquired();

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(
                        TrafficSharedBalanceSnapshot.builder()
                                .familyId(22L)
                                .amount(100L)
                                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 2, 1, 0, 0))
                                .build(),
                        TrafficSharedBalanceSnapshot.builder()
                                .familyId(22L)
                                .amount(500L)
                                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                                .build()
                );
        when(trafficRefillSourceMapper.refreshSharedBalanceIfBeforeTargetMonth(
                22L,
                LocalDateTime.of(2026, 3, 1, 0, 0)
        )).thenReturn(0);
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRefillSourceMapper).refreshSharedBalanceIfBeforeTargetMonth(
                22L,
                LocalDateTime.of(2026, 3, 1, 0, 0)
        );
        verify(trafficRemainingBalanceCacheService).hydrateSharedSnapshot("shared-balance", 500L, 1_775_833_199L);
    }

    @Test
    @DisplayName("GLOBAL_POLICY_HYDRATE가 반환되면 정책 스냅샷만 복구한 뒤 통합 Lua를 재시도한다")
    void recoverIfNeededHydratesGlobalPolicyThenRetries() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult =
                unifiedResult(0L, 0L, 0L, TrafficLuaStatus.GLOBAL_POLICY_HYDRATE);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficPolicyBootstrapService).hydrateOnDemand();
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateIndividualSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateSharedSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
    }

    private TrafficPayloadReqDto payload() {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(7)
                .apiTotalData(60L)
                .enqueuedAt(java.time.LocalDate.of(2026, 3, 1)
                        .atStartOfDay(ZoneId.of("Asia/Seoul"))
                        .toInstant()
                        .toEpochMilli())
                .build();
    }

    private TrafficLuaDeductExecutionResult unifiedResult(
            long individual,
            long shared,
            long qos,
            TrafficLuaStatus status
    ) {
        return TrafficLuaDeductExecutionResult.builder()
                .indivDeducted(individual)
                .sharedDeducted(shared)
                .qosDeducted(qos)
                .status(status)
                .build();
    }

    private void stubIndividualHydrateLockAcquired() {
        stubHydrateLockAcquired("individual-hydrate-lock");
        when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("individual-hydrate-lock");
    }

    private void stubSharedHydrateLockAcquired() {
        stubHydrateLockAcquired("shared-hydrate-lock");
        when(trafficRedisKeyFactory.sharedHydrateLockKey(22L)).thenReturn("shared-hydrate-lock");
    }

    private void stubHydrateLockAcquired(String lockKey) {
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(true);
    }
}
