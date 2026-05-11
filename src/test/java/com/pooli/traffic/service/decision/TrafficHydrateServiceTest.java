package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class TrafficHydrateServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

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

    @InjectMocks
    private TrafficHydrateService service;

    @Test
    @DisplayName("HYDRATE가 반환되면 개인/공유 잔량과 QoS를 적재한 뒤 통합 Lua를 재시도한다")
    void recoverIfNeededHydratesThenRetries() {
        TrafficPayloadReqDto payload = payload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of("trace-001");
        TrafficLuaDeductExecutionResult currentResult = unifiedResult(0L, 0L, 0L, TrafficLuaStatus.HYDRATE);
        TrafficLuaDeductExecutionResult retriedResult = unifiedResult(60L, 0L, 0L, TrafficLuaStatus.OK);
        ReflectionTestUtils.setField(service, "hydrateLockEnabled", false);

        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        when(trafficRefillSourceMapper.selectIndividualRemaining(11L)).thenReturn(-1L);
        when(trafficRefillSourceMapper.selectSharedRemaining(22L)).thenReturn(500L);
        when(trafficRefillSourceMapper.selectIndividualQosSpeedLimit(11L)).thenReturn(1L);
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(payload, 60L, context, TrafficFailureStage.HYDRATE))
                .thenReturn(retriedResult);

        TrafficLuaDeductExecutionResult result =
                service.recoverIfNeeded(payload, 60L, context, currentResult);

        assertEquals(TrafficLuaStatus.OK, result.getStatus());
        verify(trafficRemainingBalanceCacheService).hydrateBalance("individual-balance", -1L, 1_775_833_199L);
        verify(trafficRemainingBalanceCacheService).hydrateBalance("shared-balance", 500L, 1_775_833_199L);
        verify(trafficRemainingBalanceCacheService).putQos("individual-balance", 125L);
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.INDIVIDUAL);
        verify(trafficHydrateMetrics).incrementHydrate(TrafficPoolType.SHARED);
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
                .hydrateBalance(
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
}
