package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private TrafficDeductOrchestratorService trafficDeductOrchestratorService;

    @Nested
    @DisplayName("orchestrate 테스트")
    class OrchestrateTest {

        @Test
        @DisplayName("개인풀이 요청량을 전량 처리하면 SUCCESS를 반환한다")
        void successWhenIndividualHandlesAll() {
            // given
            TrafficPayloadReqDto payload = payload(103L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 103L))
                    .thenReturn(luaResult(103L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService).executeIndividualWithRecovery(payload, 103L);
            verify(trafficHydrateRefillAdapterService, never()).executeSharedWithRecovery(eq(payload), anyLong());
            verify(trafficRecentUsageBucketService).recordTickUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.INDIVIDUAL,
                    payload,
                    103L
            );
            assertAll(
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
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L))
                    .thenReturn(luaResult(4L, TrafficLuaStatus.NO_BALANCE));
            when(trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 96L))
                    .thenReturn(luaResult(96L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService).executeIndividualWithRecovery(payload, 100L);
            verify(trafficHydrateRefillAdapterService).executeSharedWithRecovery(payload, 96L);
            verify(trafficRecentUsageBucketService).recordTickUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.INDIVIDUAL,
                    payload,
                    4L
            );
            verify(trafficRecentUsageBucketService).recordTickUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.SHARED,
                    payload,
                    96L
            );

            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        @DisplayName("개인풀이 BLOCKED 상태이면 공유풀 보완 없이 PARTIAL_SUCCESS를 반환한다")
        void partialSuccessWhenIndividualBlocked() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService).executeIndividualWithRecovery(payload, 100L);
            verify(trafficHydrateRefillAdapterService, never()).executeSharedWithRecovery(eq(payload), anyLong());

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
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L))
                    .thenReturn(luaResult(-1L, TrafficLuaStatus.ERROR));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService).executeIndividualWithRecovery(payload, 100L);
            verify(trafficHydrateRefillAdapterService, never()).executeSharedWithRecovery(eq(payload), anyLong());
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.FAILED, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.ERROR, result.getLastLuaStatus())
            );
        }

        @Test
        @DisplayName("요청량이 0이면 차감 호출 없이 SUCCESS를 반환한다")
        void successWhenApiTotalDataIsZero() {
            // given
            TrafficPayloadReqDto payload = payload(0L);

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verifyNoInteractions(trafficRecentUsageBucketService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertNull(result.getLastLuaStatus())
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
