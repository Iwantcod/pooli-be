package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class TrafficDeductLuaExecutorTest {

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @InjectMocks
    private TrafficDeductLuaExecutor executor;

    @Test
    @DisplayName("통합 Lua key와 argument를 구성해 차감 결과를 반환한다")
    void executeUnifiedWithRetryReturnsUnifiedResult() {
        TrafficPayloadReqDto payload = payload();
        ReflectionTestUtils.setField(executor, "redisRetryMaxAttempts", 0);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("individual-balance");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, java.time.YearMonth.of(2026, 3)))
                .thenReturn("shared-balance");
        stubUnifiedKeys();
        when(trafficLuaScriptInfraService.executeDeductUnified(anyList(), anyList()))
                .thenReturn(unifiedResult(10L, 20L, 30L, TrafficLuaStatus.OK));

        TrafficLuaDeductExecutionResult result = executor.executeUnifiedWithRetry(
                payload,
                60L,
                TrafficDeductExecutionContext.of("trace-001"),
                TrafficFailureStage.DEDUCT
        );

        assertAll(
                () -> assertEquals(10L, result.getIndivDeducted()),
                () -> assertEquals(20L, result.getSharedDeducted()),
                () -> assertEquals(30L, result.getQosDeducted()),
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus())
        );
        verify(trafficLuaScriptInfraService).executeDeductUnified(anyList(), anyList());
    }

    private void stubUnifiedKeys() {
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(java.time.LocalDate.of(2026, 3, 1)))
                .thenReturn(1_772_539_199L);
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(java.time.YearMonth.of(2026, 3)))
                .thenReturn(1_775_833_199L);
        when(trafficRedisKeyFactory.policyKey(3L)).thenReturn("policy:shared");
        when(trafficRedisKeyFactory.policyKey(4L)).thenReturn("policy:daily");
        when(trafficRedisKeyFactory.policyKey(5L)).thenReturn("policy:app-data");
        when(trafficRedisKeyFactory.policyKey(6L)).thenReturn("policy:app-speed");
        when(trafficRedisKeyFactory.dailyTotalLimitKey(11L)).thenReturn("daily-limit");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(11L, java.time.LocalDate.of(2026, 3, 1))).thenReturn("daily-usage");
        when(trafficRedisKeyFactory.monthlySharedLimitKey(11L)).thenReturn("monthly-shared-limit");
        when(trafficRedisKeyFactory.monthlySharedUsageKey(11L, java.time.YearMonth.of(2026, 3))).thenReturn("monthly-shared-usage");
        when(trafficRedisKeyFactory.appDataDailyLimitKey(11L)).thenReturn("app-data-limit");
        when(trafficRedisKeyFactory.dailyAppUsageKey(11L, java.time.LocalDate.of(2026, 3, 1))).thenReturn("daily-app-usage");
        when(trafficRedisKeyFactory.appSpeedLimitKey(11L)).thenReturn("app-speed-limit");
        when(trafficRedisKeyFactory.speedBucketIndividualAppKey(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn("speed-bucket");
        when(trafficRedisKeyFactory.dedupeRunKey("trace-001")).thenReturn("dedupe");
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
