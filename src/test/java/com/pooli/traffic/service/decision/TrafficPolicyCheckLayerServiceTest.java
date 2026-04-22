package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficPolicyCheckLayerResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrafficPolicyCheckLayerServiceTest {

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficPolicyBootstrapService trafficPolicyBootstrapService;

    @Mock
    private TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @InjectMocks
    private TrafficPolicyCheckLayerService trafficPolicyCheckLayerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trafficPolicyCheckLayerService, "redisRetryBackoffMs", 0L);
        Mockito.lenient().when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        Mockito.lenient().when(trafficRefillOutboxSupportService.unwrapRuntimeException(any(RuntimeException.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(any())).thenReturn(false);
    }

    @Test
    void evaluateReturnsWhitelistBypassWhenPolicyCheckOkWithWhitelist() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubPolicyCheckKeys(payload.getLineId());
        when(trafficLuaScriptInfraService.executeBlockPolicyCheck(anyList(), anyList()))
                .thenReturn(luaResult(1L, TrafficLuaStatus.OK));

        // when
        TrafficPolicyCheckLayerResult result =
                trafficPolicyCheckLayerService.evaluate(payload);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                () -> assertEquals(1, result.getWhitelistBypass()),
                () -> assertFalse(result.isFallbackEligible()),
                () -> assertEquals(TrafficPolicyCheckFailureCause.NONE, result.getFailureCause())
        );
    }

    @Test
    void evaluateRetriesGlobalPolicyHydrateThenReturnsBlockedStatus() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubPolicyCheckKeys(payload.getLineId());
        when(trafficLuaScriptInfraService.executeBlockPolicyCheck(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.GLOBAL_POLICY_HYDRATE))
                .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_REPEAT));

        // when
        TrafficPolicyCheckLayerResult result =
                trafficPolicyCheckLayerService.evaluate(payload);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.BLOCKED_REPEAT, result.getStatus()),
                () -> assertEquals(0, result.getWhitelistBypass()),
                () -> assertFalse(result.isFallbackEligible()),
                () -> assertEquals(TrafficPolicyCheckFailureCause.NONE, result.getFailureCause())
        );
        verify(trafficPolicyBootstrapService, times(1)).hydrateOnDemand();
    }

    @Test
    void evaluateMarksFallbackEligibleWhenRetryablePolicyCheckFailureOccurs() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubPolicyCheckKeys(payload.getLineId());
        QueryTimeoutException timeoutException = new QueryTimeoutException("redis timeout");
        when(trafficLuaScriptInfraService.executeBlockPolicyCheck(anyList(), anyList()))
                .thenThrow(timeoutException);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(timeoutException)).thenReturn(true);

        // when
        TrafficPolicyCheckLayerResult result =
                trafficPolicyCheckLayerService.evaluate(payload);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.ERROR, result.getStatus()),
                () -> assertEquals(0, result.getWhitelistBypass()),
                () -> assertTrue(result.isFallbackEligible()),
                () -> assertEquals(TrafficPolicyCheckFailureCause.POLICY_CHECK_RETRYABLE, result.getFailureCause()),
                () -> assertSame(timeoutException, result.getFailure())
        );
    }

    @Test
    void evaluateRethrowsWhenPolicyCheckFailureIsNotRetryable() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubPolicyCheckKeys(payload.getLineId());
        QueryTimeoutException timeoutException = new QueryTimeoutException("redis timeout");
        when(trafficLuaScriptInfraService.executeBlockPolicyCheck(anyList(), anyList()))
                .thenThrow(timeoutException);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(timeoutException)).thenReturn(false);

        // when + then
        assertThrows(
                QueryTimeoutException.class,
                () -> trafficPolicyCheckLayerService.evaluate(payload)
        );
    }

    @Test
    void evaluateBuildsExpectedPolicyCheckLuaKeys() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubPolicyCheckKeys(payload.getLineId());
        when(trafficLuaScriptInfraService.executeBlockPolicyCheck(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.OK));

        // when
        trafficPolicyCheckLayerService.evaluate(payload);

        // then
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService).executeBlockPolicyCheck(keysCaptor.capture(), anyList());
        List<String> keys = keysCaptor.getValue();
        assertAll(
                () -> assertTrue(keys.contains("pooli:policy:1")),
                () -> assertTrue(keys.contains("pooli:policy:2")),
                () -> assertTrue(keys.contains("pooli:policy:7")),
                () -> assertTrue(keys.contains("pooli:app_whitelist:11")),
                () -> assertTrue(keys.contains("pooli:immediately_block_end:11")),
                () -> assertTrue(keys.contains("pooli:repeat_block:11"))
        );
    }

    private void stubPolicyCheckKeys(Long lineId) {
        when(trafficRedisKeyFactory.policyKey(1L)).thenReturn("pooli:policy:1");
        when(trafficRedisKeyFactory.policyKey(2L)).thenReturn("pooli:policy:2");
        when(trafficRedisKeyFactory.policyKey(7L)).thenReturn("pooli:policy:7");
        when(trafficRedisKeyFactory.appWhitelistKey(eq(lineId))).thenReturn("pooli:app_whitelist:11");
        when(trafficRedisKeyFactory.immediatelyBlockEndKey(eq(lineId))).thenReturn("pooli:immediately_block_end:11");
        when(trafficRedisKeyFactory.repeatBlockKey(eq(lineId))).thenReturn("pooli:repeat_block:11");
    }

    private TrafficPayloadReqDto createPayload() {
        long enqueuedAt = LocalDateTime.of(2026, 3, 11, 13, 0, 0)
                .toInstant(ZoneOffset.ofHours(9))
                .toEpochMilli();

        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(enqueuedAt)
                .build();
    }

    private TrafficLuaExecutionResult luaResult(long answer, TrafficLuaStatus status) {
        return TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build();
    }
}
