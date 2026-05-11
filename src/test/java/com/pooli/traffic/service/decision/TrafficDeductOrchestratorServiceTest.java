package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficPolicyCheckLayerResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.service.policy.TrafficLinePolicyHydrationService;
import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

@ExtendWith(MockitoExtension.class)
class TrafficDeductOrchestratorServiceTest {

    @Mock
    private TrafficDeductLuaExecutor trafficDeductLuaExecutor;

    @Mock
    private TrafficHydrateService trafficHydrateService;

    @Mock
    private TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    @Mock
    private TrafficSharedPoolThresholdAlarmService trafficSharedPoolThresholdAlarmService;

    @Mock
    private TrafficLinePolicyHydrationService trafficLinePolicyHydrationService;

    @Mock
    private TrafficPolicyCheckLayerService trafficPolicyCheckLayerService;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @InjectMocks
    private TrafficDeductOrchestratorService service;

    @BeforeEach
    void setUp() {
        lenient().when(trafficPolicyCheckLayerService.evaluate(any(TrafficPayloadReqDto.class)))
                .thenReturn(policyCheckFromLua(0L, TrafficLuaStatus.OK));
    }

    @Test
    @DisplayName("통합 Lua 결과의 개인/공유/QoS 차감량으로 최종 결과를 조립한다")
    void orchestratesUnifiedDeductResult() {
        TrafficPayloadReqDto payload = payload(100L);
        TrafficLuaDeductExecutionResult initialResult = unifiedResult(30L, 40L, 30L, TrafficLuaStatus.OK);
        when(trafficDeductLuaExecutor.executeUnifiedWithRetry(
                eq(payload),
                eq(100L),
                any(TrafficDeductExecutionContext.class),
                eq(TrafficFailureStage.DEDUCT)
        )).thenReturn(initialResult);
        when(trafficHydrateService.recoverIfNeeded(
                eq(payload),
                eq(100L),
                any(TrafficDeductExecutionContext.class),
                eq(initialResult)
        )).thenReturn(initialResult);

        TrafficDeductResultResDto result = service.orchestrate(payload);

        assertAll(
                () -> assertEquals(TrafficFinalStatus.SUCCESS, result.getFinalStatus()),
                () -> assertEquals(100L, result.getDeductedTotalBytes()),
                () -> assertEquals(30L, result.getDeductedIndividualBytes()),
                () -> assertEquals(40L, result.getDeductedSharedBytes()),
                () -> assertEquals(30L, result.getDeductedQosBytes()),
                () -> assertEquals(0L, result.getApiRemainingData())
        );
        verify(trafficRecentUsageBucketService).recordUsage(TrafficPoolType.INDIVIDUAL, payload, 30L);
        verify(trafficRecentUsageBucketService).recordUsage(TrafficPoolType.SHARED, payload, 40L);
        verify(trafficSharedPoolThresholdAlarmService).checkAndEnqueueIfReached(22L);
    }

    @Test
    @DisplayName("정책 차단 상태면 차감 Lua를 호출하지 않고 NOT_DEDUCTED로 종료한다")
    void returnsNotDeductedWhenPolicyBlocks() {
        TrafficPayloadReqDto payload = payload(100L);
        when(trafficPolicyCheckLayerService.evaluate(payload))
                .thenReturn(policyCheckFromLua(0L, TrafficLuaStatus.BLOCKED_REPEAT));

        TrafficDeductResultResDto result = service.orchestrate(payload);

        assertAll(
                () -> assertEquals(TrafficFinalStatus.NOT_DEDUCTED, result.getFinalStatus()),
                () -> assertEquals(0L, result.getDeductedTotalBytes()),
                () -> assertEquals(100L, result.getApiRemainingData()),
                () -> assertEquals(TrafficLuaStatus.BLOCKED_REPEAT, result.getLastLuaStatus())
        );
        verify(trafficDeductLuaExecutor, never())
                .executeUnifiedWithRetry(any(), any(Long.class), any(), any());
        verify(trafficHydrateService, never())
                .recoverIfNeeded(any(), any(Long.class), any(), any());
    }

    @Test
    @DisplayName("정책 검증 retryable 장애도 DB fallback 없이 차감 0 결과로 종료한다")
    void doesNotUseDbFallbackWhenPolicyCheckFailsRetryable() {
        TrafficPayloadReqDto payload = payload(100L);
        TrafficStageFailureException stageFailure = TrafficStageFailureException.retryableFailure(
                TrafficFailureStage.POLICY_CHECK,
                new QueryTimeoutException("policy redis timeout")
        );
        when(trafficPolicyCheckLayerService.evaluate(payload))
                .thenReturn(TrafficPolicyCheckLayerResult.retryableFailure(
                        TrafficPolicyCheckFailureCause.POLICY_CHECK_RETRYABLE,
                        stageFailure
                ));

        TrafficDeductResultResDto result = service.orchestrate(payload);

        assertAll(
                () -> assertEquals(TrafficFinalStatus.FAILED, result.getFinalStatus()),
                () -> assertEquals(TrafficLuaStatus.ERROR, result.getLastLuaStatus()),
                () -> assertEquals(100L, result.getApiRemainingData())
        );
        verify(trafficDeductLuaExecutor, never())
                .executeUnifiedWithRetry(any(), any(Long.class), any(), any());
        verify(trafficHydrateService, never())
                .recoverIfNeeded(any(), any(Long.class), any(), any());
    }

    @Test
    @DisplayName("ensureLoaded non-retryable 실패는 원 예외로 전파한다")
    void throwsWhenEnsureLoadedFailureIsNonRetryable() {
        TrafficPayloadReqDto payload = payload(100L);
        QueryTimeoutException exception = new QueryTimeoutException("policy check failed");
        doThrow(exception).when(trafficLinePolicyHydrationService).ensureLoaded(11L);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)).thenReturn(false);

        QueryTimeoutException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                QueryTimeoutException.class,
                () -> service.orchestrate(payload)
        );

        assertEquals(exception, thrown);
    }

    private TrafficPayloadReqDto payload(long apiTotalData) {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(7)
                .apiTotalData(apiTotalData)
                .build();
    }

    private TrafficPolicyCheckLayerResult policyCheckFromLua(long answer, TrafficLuaStatus status) {
        return TrafficPolicyCheckLayerResult.fromLuaResult(TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build());
    }

    private TrafficLuaDeductExecutionResult unifiedResult(long individual, long shared, long qos, TrafficLuaStatus status) {
        return TrafficLuaDeductExecutionResult.builder()
                .indivDeducted(individual)
                .sharedDeducted(shared)
                .qosDeducted(qos)
                .status(status)
                .build();
    }
}
