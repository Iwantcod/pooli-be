package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.monitoring.metrics.TrafficTickMetrics;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;

@ExtendWith(MockitoExtension.class)
class TrafficDeductOrchestratorServiceTest {

    @Mock
    private TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;

    @Mock
    private TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    @Mock
    private TrafficTickPacer trafficTickPacer;

    @Mock
    private TrafficTickMetrics trafficTickMetrics;

    @InjectMocks
    private TrafficDeductOrchestratorService trafficDeductOrchestratorService;

    @Nested
    @DisplayName("orchestrate 테스트")
    class OrchestrateTest {

        @Test
        @DisplayName("ceil 분배 규칙으로 10 tick 처리 후 SUCCESS")
        void successWithCeilTickDistribution() {
            // given
            TrafficPayloadReqDto payload = payload(103L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), anyLong()))
                    .thenAnswer(invocation -> {
                        long target = invocation.getArgument(1, Long.class);
                        // 테스트 단순화를 위해 개인풀에서 tick 목표량을 모두 차감한다고 가정한다.
                        return luaResult(target, TrafficLuaStatus.OK);
                    });

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            ArgumentCaptor<Long> targetCaptor = ArgumentCaptor.forClass(Long.class);
            verify(trafficHydrateRefillAdapterService, times(10)).executeIndividualWithRecovery(eq(payload), targetCaptor.capture());
            verify(trafficHydrateRefillAdapterService, never()).executeSharedWithRecovery(eq(payload), anyLong());
            verify(trafficTickPacer, times(10)).awaitTickStart(anyLong(), anyInt());

            assertAll(
                    () -> assertEquals(List.of(11L, 11L, 11L, 10L, 10L, 10L, 10L, 10L, 10L, 10L), targetCaptor.getAllValues()),
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(103L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        @DisplayName("개인풀 NO_BALANCE + residual 발생 시 공유풀 보완 차감")
        void individualNoBalanceThenSharedCompensation() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), anyLong()))
                    .thenReturn(luaResult(4L, TrafficLuaStatus.NO_BALANCE))
                    .thenAnswer(invocation -> {
                        long target = invocation.getArgument(1, Long.class);
                        return luaResult(target, TrafficLuaStatus.OK);
                    });
            when(trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 6L))
                    .thenReturn(luaResult(6L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService, times(1))
                    .executeSharedWithRecovery(payload, 6L);
            verify(trafficTickPacer, times(10)).awaitTickStart(anyLong(), anyInt());

            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        @DisplayName("회복 불가 상태(BLOCKED_*)면 즉시 종료하고 PARTIAL_SUCCESS")
        void stopImmediatelyOnUnrecoverableStatus() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 10L))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService, times(1))
                    .executeIndividualWithRecovery(payload, 10L);
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong());
            verify(trafficTickPacer, times(1)).awaitTickStart(anyLong(), anyInt());

            assertAll(
                    () -> assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getLastLuaStatus())
            );
        }

        @Test
        @DisplayName("ERROR 상태면 즉시 종료하고 FAILED")
        void failedOnErrorStatus() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 10L))
                    .thenReturn(luaResult(-1L, TrafficLuaStatus.ERROR));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficTickPacer, times(1)).awaitTickStart(anyLong(), anyInt());
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.FAILED, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.ERROR, result.getLastLuaStatus())
            );
        }
    }

    private TrafficPayloadReqDto payload(long apiTotalData) {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(apiTotalData)
                .enqueuedAt(System.currentTimeMillis())
                .build();
    }

    private TrafficLuaExecutionResult luaResult(long answer, TrafficLuaStatus status) {
        return TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build();
    }
}
