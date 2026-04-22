package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.springframework.dao.DataAccessResourceFailureException;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import com.pooli.traffic.service.runtime.TrafficBalanceStateWriteThroughService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.policy.TrafficLinePolicyHydrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficPolicyCheckLayerResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import com.pooli.traffic.domain.enums.TrafficPoolType;

@ExtendWith(MockitoExtension.class)
class TrafficDeductOrchestratorServiceTest {

    @Mock
    private TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;

    @Mock
    private TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    @Mock
    private TrafficSharedPoolThresholdAlarmService trafficSharedPoolThresholdAlarmService;

    @Mock
    private TrafficBalanceStateWriteThroughService trafficBalanceStateWriteThroughService;

    @Mock
    private TrafficLinePolicyHydrationService trafficLinePolicyHydrationService;

    @Mock
    private TrafficPolicyCheckLayerService trafficPolicyCheckLayerService;

    @Mock
    private TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private TrafficDbDeductFallbackService trafficDbDeductFallbackService;

    @Mock
    private TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;

    @Mock
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @InjectMocks
    private TrafficDeductOrchestratorService trafficDeductOrchestratorService;

    @Nested
    class OrchestrateTest {

        @BeforeEach
        void setUp() {
            lenient().when(trafficPolicyCheckLayerService.evaluate(any(TrafficPayloadReqDto.class)))
                    .thenReturn(policyCheckFromLua(0L, TrafficLuaStatus.OK));
        }

        @Test
        void successWhenIndividualHandlesAll() {
            // given
            TrafficPayloadReqDto payload = payload(103L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(103L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(103L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(103L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verify(trafficRecentUsageBucketService).recordUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.INDIVIDUAL,
                    payload,
                    103L
            );
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(103L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void individualNoBalanceThenSharedCompensation() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(4L, TrafficLuaStatus.NO_BALANCE));
            when(trafficHydrateRefillAdapterService.executeSharedWithRecovery(eq(payload), eq(96L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(96L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService)
                    .executeSharedWithRecovery(eq(payload), eq(96L), any(TrafficDeductExecutionContext.class));
            verify(trafficRecentUsageBucketService).recordUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.INDIVIDUAL,
                    payload,
                    4L
            );
            verify(trafficRecentUsageBucketService).recordUsage(
                    com.pooli.traffic.domain.enums.TrafficPoolType.SHARED,
                    payload,
                    96L
            );
            verify(trafficBalanceStateWriteThroughService).markSharedMetaConsumed(22L, 96L);
            verify(trafficSharedPoolThresholdAlarmService).checkAndEnqueueIfReached(22L);

            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void successWhenSharedDeductedButThresholdAlarmThrowsException() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(4L, TrafficLuaStatus.NO_BALANCE));
            when(trafficHydrateRefillAdapterService.executeSharedWithRecovery(eq(payload), eq(96L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(96L, TrafficLuaStatus.OK));
            doThrow(new IllegalStateException("alarm enqueue failed"))
                    .when(trafficSharedPoolThresholdAlarmService)
                    .checkAndEnqueueIfReached(22L);

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService)
                    .executeSharedWithRecovery(eq(payload), eq(96L), any(TrafficDeductExecutionContext.class));
            verify(trafficBalanceStateWriteThroughService).markSharedMetaConsumed(22L, 96L);
            verify(trafficSharedPoolThresholdAlarmService).checkAndEnqueueIfReached(22L);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void successWhenSharedMetaWriteThroughThrowsException() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(4L, TrafficLuaStatus.NO_BALANCE));
            when(trafficHydrateRefillAdapterService.executeSharedWithRecovery(eq(payload), eq(96L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(96L, TrafficLuaStatus.OK));
            doThrow(new DataAccessResourceFailureException("meta write through failed"))
                    .when(trafficBalanceStateWriteThroughService)
                    .markSharedMetaConsumed(22L, 96L);

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficBalanceStateWriteThroughService).markSharedMetaConsumed(22L, 96L);
            verify(trafficSharedPoolThresholdAlarmService).checkAndEnqueueIfReached(22L);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void noSharedWhenResidualIsZeroEvenNoBalance() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(100L, TrafficLuaStatus.NO_BALANCE));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getLastLuaStatus())
            );
        }

        @Test
        void noSharedWhenIndividualStatusIsNotNoBalance() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(30L, TrafficLuaStatus.HIT_DAILY_LIMIT));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(30L, result.getDeductedTotalBytes()),
                    () -> assertEquals(70L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.HIT_DAILY_LIMIT, result.getLastLuaStatus())
            );
        }

        @Test
        void partialSuccessWhenIndividualBlocked() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);

            assertAll(
                    () -> assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getLastLuaStatus())
            );
        }

        @Test
        void failedOnErrorStatus() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(-1L, TrafficLuaStatus.ERROR));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.FAILED, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.ERROR, result.getLastLuaStatus())
            );
        }

        @Test
        void successWhenApiTotalDataIsZero() {
            // given
            TrafficPayloadReqDto payload = payload(0L);

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verifyNoInteractions(trafficRecentUsageBucketService);
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertNull(result.getLastLuaStatus())
            );
        }

        @Test
        void successWhenApiTotalDataIsNegative() {
            // given
            TrafficPayloadReqDto payload = payload(-10L);

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verifyNoInteractions(trafficRecentUsageBucketService);
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertNull(result.getLastLuaStatus())
            );
        }

        @Test
        void failedWhenAdapterThrowsException() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenThrow(new RuntimeException("adapter failed"));

            // when + then
            assertThrows(RuntimeException.class, () -> trafficDeductOrchestratorService.orchestrate(payload));
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
            verify(trafficHydrateRefillAdapterService, never())
                    .executeSharedWithRecovery(eq(payload), anyLong(), any(TrafficDeductExecutionContext.class));
            verifyNoInteractions(trafficRecentUsageBucketService);
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
        }

        @Test
        void blocksBeforeDeductWhenPolicyCheckBlocked() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            when(trafficPolicyCheckLayerService.evaluate(eq(payload)))
                    .thenReturn(policyCheckFromLua(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
            verify(trafficPolicyCheckLayerService).evaluate(eq(payload));
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verifyNoInteractions(trafficDbDeductFallbackService);
            verifyNoInteractions(trafficRecentUsageBucketService);
            verifyNoInteractions(trafficSharedPoolThresholdAlarmService);
            verifyNoInteractions(trafficBalanceStateWriteThroughService);
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(0L, result.getDeductedTotalBytes()),
                    () -> assertEquals(100L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getLastLuaStatus())
            );
        }

        @Test
        void executesDbFallbackWhenPolicyCheckIsFallbackEligible() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            RuntimeException policyFailure = new RuntimeException("policy check redis timeout");
            when(trafficPolicyCheckLayerService.evaluate(eq(payload)))
                    .thenReturn(TrafficPolicyCheckLayerResult.retryableFailure(
                            TrafficPolicyCheckFailureCause.POLICY_CHECK_RETRYABLE,
                            policyFailure
                    ));
            when(trafficDbDeductFallbackService.deduct(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(100L),
                    any(TrafficDeductExecutionContext.class)
            )).thenReturn(luaResult(40L, TrafficLuaStatus.NO_BALANCE));
            when(trafficDbDeductFallbackService.deduct(
                    eq(TrafficPoolType.SHARED),
                    eq(payload),
                    eq(60L),
                    any(TrafficDeductExecutionContext.class)
            )).thenReturn(luaResult(60L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
            verify(trafficPolicyCheckLayerService).evaluate(eq(payload));
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verify(trafficDbDeductFallbackService).deduct(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(100L),
                    any(TrafficDeductExecutionContext.class)
            );
            verify(trafficDbDeductFallbackService).deduct(
                    eq(TrafficPoolType.SHARED),
                    eq(payload),
                    eq(60L),
                    any(TrafficDeductExecutionContext.class)
            );
            verify(trafficDeductFallbackMetrics).incrementDbFallback("INDIVIDUAL", "policy_check_retryable_failure");
            verify(trafficDeductFallbackMetrics).incrementDbFallback("SHARED", "policy_check_retryable_failure");
            verify(trafficInFlightDedupeService, times(2)).markDbFallback("trace-001");
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void executesDbFallbackWhenEnsureLoadedFailsRetryable() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            DataAccessResourceFailureException ensureLoadedFailure = new DataAccessResourceFailureException("redis timeout");
            RuntimeException unwrapped = new RuntimeException("redis timeout");
            doThrow(ensureLoadedFailure)
                    .when(trafficLinePolicyHydrationService)
                    .ensureLoaded(11L);
            when(trafficRefillOutboxSupportService.unwrapRuntimeException(ensureLoadedFailure)).thenReturn(unwrapped);
            when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrapped)).thenReturn(true);
            when(trafficDbDeductFallbackService.deduct(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(100L),
                    any(TrafficDeductExecutionContext.class)
            )).thenReturn(luaResult(100L, TrafficLuaStatus.OK));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
            verifyNoInteractions(trafficPolicyCheckLayerService);
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verify(trafficDbDeductFallbackService).deduct(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(100L),
                    any(TrafficDeductExecutionContext.class)
            );
            verify(trafficDeductFallbackMetrics).incrementDbFallback("INDIVIDUAL", "policy_check_retryable_failure");
            verify(trafficInFlightDedupeService).markDbFallback("trace-001");
            assertAll(
                    () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                    () -> assertEquals(100L, result.getDeductedTotalBytes()),
                    () -> assertEquals(0L, result.getApiRemainingData()),
                    () -> assertEquals(TrafficLuaStatus.OK, result.getLastLuaStatus())
            );
        }

        @Test
        void rethrowsWhenEnsureLoadedFailsNonRetryable() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            ApplicationException ensureLoadedFailure = new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            RuntimeException unwrapped = new RuntimeException("not redis infra failure");
            doThrow(ensureLoadedFailure)
                    .when(trafficLinePolicyHydrationService)
                    .ensureLoaded(11L);
            when(trafficRefillOutboxSupportService.unwrapRuntimeException(ensureLoadedFailure)).thenReturn(unwrapped);
            when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrapped)).thenReturn(false);

            // when + then
            assertThrows(ApplicationException.class, () -> trafficDeductOrchestratorService.orchestrate(payload));
            verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
            verifyNoInteractions(trafficPolicyCheckLayerService);
            verifyNoInteractions(trafficHydrateRefillAdapterService);
            verifyNoInteractions(trafficDbDeductFallbackService);
        }

        @Test
        void skipsPreCheckAndUsesAdapterWhenPreCheckMinimumFieldsAreMissing() {
            // given
            TrafficPayloadReqDto payload = payload(100L);
            payload.setAppId(null);
            when(trafficHydrateRefillAdapterService.executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class)))
                    .thenReturn(luaResult(-1L, TrafficLuaStatus.ERROR));

            // when
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // then
            verifyNoInteractions(trafficLinePolicyHydrationService);
            verifyNoInteractions(trafficPolicyCheckLayerService);
            verifyNoInteractions(trafficDbDeductFallbackService);
            verify(trafficHydrateRefillAdapterService)
                    .executeIndividualWithRecovery(eq(payload), eq(100L), any(TrafficDeductExecutionContext.class));
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

    private TrafficPolicyCheckLayerResult policyCheckFromLua(long answer, TrafficLuaStatus status) {
        return TrafficPolicyCheckLayerResult.fromLuaResult(luaResult(answer, status));
    }
}
