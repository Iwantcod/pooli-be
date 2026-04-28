package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.retry.TrafficDeductLuaRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficDeductLuaRetryInvoker;
import com.pooli.traffic.service.retry.TrafficDeductLuaRetryOperation;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.monitoring.metrics.TrafficRefillMetrics;
import com.pooli.traffic.domain.TrafficDbRefillClaimResult;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.domain.enums.TrafficRefillGateStatus;

@ExtendWith(MockitoExtension.class)
class TrafficHydrateRefillAdapterServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficQuotaSourcePort trafficQuotaSourcePort;

    @Mock
    private TrafficQuotaCacheService trafficQuotaCacheService;

    @Mock
    private TrafficPolicyBootstrapService trafficPolicyBootstrapService;

    @Mock
    private TrafficHydrateMetrics trafficHydrateMetrics;

    @Mock
    private TrafficRefillMetrics trafficRefillMetrics;

    @Mock
    private TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @Mock
    private TrafficDeductLuaRetryInvoker trafficDeductLuaRetryInvoker;

    @InjectMocks
    private TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trafficHydrateRefillAdapterService, "hydrateLockEnabled", true);
        ReflectionTestUtils.setField(trafficHydrateRefillAdapterService, "redisRetryBackoffMs", 0L);
        Mockito.lenient().when(trafficRefillOutboxSupportService.resolveIdempotencyKey(anyString()))
                .thenAnswer(invocation -> "pooli:refill:idempotency:" + invocation.getArgument(0, String.class));
        Mockito.lenient().when(trafficRefillOutboxSupportService.refillIdempotencyTtlSeconds()).thenReturn(600L);
        Mockito.lenient().when(trafficQuotaCacheService.applyRefillWithIdempotency(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(true);
        Mockito.lenient().when(trafficRefillOutboxSupportService.unwrapRuntimeException(any(RuntimeException.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(trafficRedisFailureClassifier.isConnectionFailure(any())).thenReturn(false);
        Mockito.lenient().when(trafficRedisFailureClassifier.isTimeoutFailure(any())).thenReturn(false);
        Mockito.lenient().when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(any())).thenReturn(false);
        Mockito.lenient().when(trafficDeductLuaRetryInvoker.execute(any()))
                .thenAnswer(invocation -> {
                    TrafficDeductLuaRetryOperation operation =
                            invocation.getArgument(0, TrafficDeductLuaRetryOperation.class);
                    RuntimeException lastRetryableFailure = null;
                    for (int attempt = 1; attempt <= 4; attempt++) {
                        try {
                            return TrafficDeductLuaRetryExecutionResult.success(
                                    operation.execute(),
                                    attempt,
                                    lastRetryableFailure
                            );
                        } catch (RuntimeException exception) {
                            RuntimeException unwrappedException =
                                    trafficRefillOutboxSupportService.unwrapRuntimeException(exception);
                            if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrappedException)) {
                                return TrafficDeductLuaRetryExecutionResult.nonRetryableFailure(
                                        unwrappedException,
                                        attempt,
                                        lastRetryableFailure
                                );
                            }
                            lastRetryableFailure = unwrappedException;
                        }
                    }
                    return TrafficDeductLuaRetryExecutionResult.retryableFailure(lastRetryableFailure, 4);
                });
    }

    @Nested
    class ExecuteIndividualWithRecoveryTest {

        @Test
        void globalPolicyHydrateThenRetrySameLuaSuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.GLOBAL_POLICY_HYDRATE))
                    .thenReturn(luaResult(80L, TrafficLuaStatus.OK));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(80L, result.getAnswer())
            );
            verify(trafficPolicyBootstrapService, times(1)).hydrateOnDemand();
            verify(trafficLuaScriptInfraService, times(2)).executeDeductIndividual(anyList(), anyList());
            verify(trafficQuotaCacheService, never()).hydrateBalance(anyString(), anyLong());
        }

        @Test
        void keepsGlobalPolicyHydrateWhenRetryExhausted() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.GLOBAL_POLICY_HYDRATE));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.GLOBAL_POLICY_HYDRATE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficPolicyBootstrapService, times(5)).hydrateOnDemand();
            verify(trafficLuaScriptInfraService, times(6)).executeDeductIndividual(anyList(), anyList());
        }

        @Test
        void hydrateThenRetrySuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("pooli:indiv_hydrate_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    "pooli:indiv_hydrate_lock:11",
                    payload.getTraceId(),
                    java.time.Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
            )).thenReturn(true);
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE))
                    .thenReturn(luaResult(80L, TrafficLuaStatus.OK));
            when(trafficQuotaSourcePort.loadIndividualQosSpeedLimit(payload)).thenReturn(2_500L);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(80L, result.getAnswer())
            );
            verify(trafficQuotaCacheService).hydrateBalance("pooli:remaining_indiv_amount:11:202603", 1_770_000_000L);
            verify(trafficQuotaCacheService).putQos("pooli:remaining_indiv_amount:11:202603", 2_500L);
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_hydrate_lock:11", payload.getTraceId());
        }

        @Test
        void hydrateLockMissThenRetrySuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("pooli:indiv_hydrate_lock:11");
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    "pooli:indiv_hydrate_lock:11",
                    payload.getTraceId(),
                    java.time.Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
            )).thenReturn(false);
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE))
                    .thenReturn(luaResult(40L, TrafficLuaStatus.OK));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(40L, result.getAnswer())
            );
            verify(trafficQuotaCacheService, never()).hydrateBalance(anyString(), anyLong());
            verify(trafficLuaScriptInfraService, never()).executeLockRelease("pooli:indiv_hydrate_lock:11", payload.getTraceId());
            verify(trafficLuaScriptInfraService, times(2)).executeDeductIndividual(anyList(), anyList());
        }

        @Test
        void refillGateOkThenRetrySuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);

            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                    .thenReturn(luaResult(60L, TrafficLuaStatus.OK));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true, true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 1_000L, 100L, 900L));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(60L, result.getAnswer())
            );
            // writeDbEmptyFlag는 더 이상 별도 호출되지 않는다 — Lua 내부에서 원자적으로 처리된다.
            verify(trafficQuotaCacheService, never()).writeDbEmptyFlag(anyString(), anyBoolean());
            verify(trafficQuotaCacheService, never()).applyRefillWithIdempotency(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyLong(),
                    anyLong(),
                    anyLong(),
                    anyBoolean()
            );
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
        }

        @Test
        void returnsHitAppSpeedAfterRefillRetryWhenSpeedCapApplies() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);

            // 첫 시도에서 50을 차감하고 NO_BALANCE로 리필 경로를 연다.
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(50L, TrafficLuaStatus.NO_BALANCE))
                    .thenReturn(luaResult(10L, TrafficLuaStatus.HIT_APP_SPEED));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true, true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 50L, 50L, 0L));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.HIT_APP_SPEED, result.getStatus()),
                    () -> assertEquals(60L, result.getAnswer())
            );

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
            verify(trafficLuaScriptInfraService, times(2)).executeDeductIndividual(anyList(), argsCaptor.capture());
            List<List<String>> allArgs = argsCaptor.getAllValues();
            assertAll(
                    () -> assertEquals("100", allArgs.get(0).get(0)),
                    () -> assertEquals("50", allArgs.get(1).get(0))
            );
        }

        @Test
        void skipsDbFallbackWhenRedisRetryExhaustedAfterRefillApplied() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                    .thenThrow(new QueryTimeoutException("redis timeout"))
                    .thenThrow(new QueryTimeoutException("redis timeout"))
                    .thenThrow(new QueryTimeoutException("redis timeout"));
            when(trafficRedisFailureClassifier.isTimeoutFailure(any())).thenReturn(true);
            when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(any())).thenReturn(true);
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 1_000L, 100L, 900L));

            // when + then
            TrafficStageFailureException stageFailure = assertThrows(
                    TrafficStageFailureException.class,
                    () -> trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L)
            );
            assertAll(
                    () -> assertEquals(TrafficFailureStage.REFILL, stageFailure.getStage()),
                    () -> assertTrue(stageFailure.isRetryableInfrastructureFailure()),
                    () -> assertEquals("traffic_refill_redis_retry_exhausted", stageFailure.getMessage()),
                    () -> assertTrue(stageFailure.getCause() instanceof QueryTimeoutException)
            );
            verify(trafficInFlightDedupeService, never()).markDbFallback(anyString());
            verify(redisOutboxRecordService).markFail(701L);
        }

        @Test
        void refillGateWaitKeepsNoBalance() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");

            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.WAIT);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficQuotaCacheService, never()).refillBalance(anyString(), anyLong(), anyLong());
            verify(trafficLuaScriptInfraService, never()).executeLockRelease(anyString(), anyString());
        }

        @Test
        void keepsNoBalanceWhenGateSkipsByThreshold() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.SKIP_THRESHOLD);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficLuaScriptInfraService).executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            );
            verify(trafficQuotaSourcePort, never()).claimRefillAmountFromDb(any(), any(), any(), anyLong(), anyString());
            verify(trafficQuotaCacheService, never()).refillBalance(anyString(), anyLong(), anyLong());
        }

        @Test
        void writesDbEmptyFlagTrueWhenDbRemainingAfterIsZero() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 0L, 0L, 0L));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus());
            // db_noop이더라도 DB after가 0이면 is_empty=1을 기록한다.
            verify(trafficQuotaCacheService).writeDbEmptyFlag("pooli:remaining_indiv_amount:11:202603", true);
            verify(trafficQuotaCacheService, never()).applyRefillWithIdempotency(
                    anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
        }

        @Test
        void invalidPayloadReturnsError() {
            // given
            TrafficPayloadReqDto invalidPayload = TrafficPayloadReqDto.builder()
                    .traceId("")
                    .lineId(11L)
                    .familyId(22L)
                    .appId(33)
                    .apiTotalData(100L)
                    .enqueuedAt(System.currentTimeMillis())
                    .build();

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(invalidPayload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.ERROR, result.getStatus()),
                    () -> assertEquals(-1L, result.getAnswer())
            );
            verifyNoInteractions(trafficLuaScriptInfraService);
        }

        @Test
        void executesDeductWithoutCallingPolicyPrecheckLayers() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(1L, TrafficLuaStatus.OK));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertEquals(TrafficLuaStatus.OK, result.getStatus());
            verify(trafficLuaScriptInfraService).executeDeductIndividual(anyList(), anyList());
        }

        @Test
        void doesNotUsePolicyCheckLayerOrDbFallbackInAdapter() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(30L, TrafficLuaStatus.OK));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(30L, result.getAnswer())
            );
            verify(trafficLuaScriptInfraService).executeDeductIndividual(anyList(), anyList());
            verify(trafficInFlightDedupeService, never()).markDbFallback(anyString());
            verify(trafficDeductFallbackMetrics, never()).incrementDbFallback(anyString(), anyString());
        }

        @Test
        void includesPolicyKeysForIndividualDeduct() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getStatus());
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            verify(trafficLuaScriptInfraService).executeDeductIndividual(keysCaptor.capture(), anyList());
            List<String> keys = keysCaptor.getValue();
            assertAll(
                    () -> assertTrue(keys.contains("pooli:policy:1")),
                    () -> assertTrue(keys.contains("pooli:policy:2")),
                    () -> assertTrue(keys.contains("pooli:policy:4")),
                    () -> assertTrue(keys.contains("pooli:policy:5")),
                    () -> assertTrue(keys.contains("pooli:policy:6")),
                    () -> assertTrue(keys.contains("pooli:policy:7"))
            );
        }

        @Test
        void keepsNoBalanceWhenLockNotOwned() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(false);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficQuotaSourcePort, never()).claimRefillAmountFromDb(any(), any(), any(), anyLong(), anyString());
            verify(trafficQuotaCacheService, never()).refillBalance(anyString(), anyLong(), anyLong());
            verify(trafficLuaScriptInfraService, never()).executeLockRelease(anyString(), anyString());
            verify(trafficRefillMetrics).increment("INDIVIDUAL", "lock_not_owned");
        }

        @Test
        void keepsNoBalanceWhenDbClaimNoopAndDbRemainingAfterPositive() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 10L, 0L, 10L));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficQuotaCacheService, never()).refillBalance(anyString(), anyLong(), anyLong());
            verify(trafficQuotaCacheService, never()).writeDbEmptyFlag(anyString(), anyBoolean());
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
            verify(trafficRefillMetrics).increment("INDIVIDUAL", "db_noop");
        }

        @Test
        void keepsNoBalanceWhenDbEmptyFlagWriteFails() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenReturn(claimResult(100L, 0L, 0L, 0L));
            doThrow(new IllegalStateException("redis unavailable"))
                    .when(trafficQuotaCacheService)
                    .writeDbEmptyFlag("pooli:remaining_indiv_amount:11:202603", true);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficQuotaCacheService).writeDbEmptyFlag("pooli:remaining_indiv_amount:11:202603", true);
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
            verify(trafficRefillMetrics).increment("INDIVIDUAL", "db_noop");
        }

        @Test
        void releasesLockWhenDbClaimThrows() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
                    "pooli:remaining_indiv_amount:11:202603",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    0L,
                    30L
            )).thenReturn(TrafficRefillGateStatus.OK);
            when(trafficLuaScriptInfraService.executeLockHeartbeat(
                    "pooli:indiv_refill_lock:11",
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            )).thenReturn(true);
            when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                    eq(TrafficPoolType.INDIVIDUAL),
                    eq(payload),
                    eq(java.time.YearMonth.of(2026, 3)),
                    eq(100L),
                    anyString()
            )).thenThrow(new IllegalStateException("db unavailable"));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
            verify(trafficRefillMetrics).increment("INDIVIDUAL", "db_error");
        }

        @Test
        void returnsErrorWhenStillHydrateAfterRetry() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("pooli:indiv_hydrate_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    "pooli:indiv_hydrate_lock:11",
                    payload.getTraceId(),
                    java.time.Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
            )).thenReturn(true);
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.HYDRATE, result.getStatus()),
                    () -> assertEquals(0L, result.getAnswer())
            );
            verify(trafficQuotaCacheService, times(5))
                    .hydrateBalance("pooli:remaining_indiv_amount:11:202603", 1_770_000_000L);
            verify(trafficLuaScriptInfraService, never())
                    .executeRefillGate(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong());
        }

        @Test
        void propagatesHydrateStageExceptionWhenRedisRetryExhaustedDuringHydrateRetryDeduct() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of(payload.getTraceId());
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("pooli:indiv_hydrate_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    "pooli:indiv_hydrate_lock:11",
                    payload.getTraceId(),
                    java.time.Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
            )).thenReturn(true);
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE))
                    .thenThrow(new QueryTimeoutException("redis timeout"))
                    .thenThrow(new QueryTimeoutException("redis timeout"))
                    .thenThrow(new QueryTimeoutException("redis timeout"))
                    .thenThrow(new QueryTimeoutException("redis timeout"));
            when(trafficRedisFailureClassifier.isTimeoutFailure(any())).thenReturn(true);
            when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(any())).thenReturn(true);

            // when + then
            TrafficStageFailureException stageFailure = assertThrows(
                    TrafficStageFailureException.class,
                    () -> trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L, context)
            );
            assertAll(
                    () -> assertEquals(TrafficFailureStage.HYDRATE, stageFailure.getStage()),
                    () -> assertTrue(stageFailure.isRetryableInfrastructureFailure()),
                    () -> assertEquals("traffic_hydrate_redis_retry_exhausted", stageFailure.getMessage()),
                    () -> assertTrue(stageFailure.getCause() instanceof QueryTimeoutException)
            );
            verify(trafficLuaScriptInfraService, times(5)).executeDeductIndividual(anyList(), anyList());
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 1);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 2);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 3);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 4);
        }

        @Test
        void propagatesDeductStageExceptionWhenRedisRetryExhausted() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of(payload.getTraceId());
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                    .thenThrow(new QueryTimeoutException("redis timeout"));
            when(trafficRefillOutboxSupportService.unwrapRuntimeException(any(RuntimeException.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(trafficRedisFailureClassifier.isTimeoutFailure(any())).thenReturn(true);
            when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(any())).thenReturn(true);
            // when + then
            TrafficStageFailureException stageFailure = assertThrows(
                    TrafficStageFailureException.class,
                    () -> trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L, context)
            );
            assertAll(
                    () -> assertEquals(TrafficFailureStage.DEDUCT, stageFailure.getStage()),
                    () -> assertTrue(stageFailure.isRetryableInfrastructureFailure()),
                    () -> assertEquals("traffic_deduct_redis_retry_exhausted", stageFailure.getMessage()),
                    () -> assertTrue(stageFailure.getCause() instanceof QueryTimeoutException)
            );
            verify(trafficLuaScriptInfraService, times(4)).executeDeductIndividual(anyList(), anyList());
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 1);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 2);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 3);
            verify(trafficInFlightDedupeService).markRedisRetry(payload.getTraceId(), 4);
            verify(trafficInFlightDedupeService, never()).markDbFallback(anyString());
        }
    }

    @Test
    void globalPolicyHydrateThenRetrySameSharedLuaSuccess() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.GLOBAL_POLICY_HYDRATE))
                .thenReturn(luaResult(30L, TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 100L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT, result.getStatus()),
                () -> assertEquals(30L, result.getAnswer())
        );
        verify(trafficPolicyBootstrapService, times(1)).hydrateOnDemand();
        verify(trafficLuaScriptInfraService, times(2)).executeDeductShared(anyList(), anyList());
    }

    @Test
    void includesPolicyAndMonthlyKeysForSharedDeduct() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 100L);

        // then
        assertEquals(TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT, result.getStatus());
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService).executeDeductShared(keysCaptor.capture(), argsCaptor.capture());
        List<String> keys = keysCaptor.getValue();
        List<String> args = argsCaptor.getValue();
        assertAll(
                () -> assertTrue(keys.contains("pooli:policy:1")),
                () -> assertTrue(keys.contains("pooli:policy:2")),
                () -> assertTrue(keys.contains("pooli:policy:3")),
                () -> assertTrue(keys.contains("pooli:policy:4")),
                () -> assertTrue(keys.contains("pooli:policy:5")),
                () -> assertTrue(keys.contains("pooli:policy:6")),
                () -> assertTrue(keys.contains("pooli:policy:7")),
                () -> assertTrue(keys.contains("pooli:monthly_shared_limit:11")),
                () -> assertTrue(keys.contains("pooli:monthly_shared_usage:11:202603")),
                () -> assertTrue(keys.contains("pooli:remaining_indiv_amount:11:202603")),
                // 공유 DB 리필 이전 호출은 QOS fallback을 비활성 상태(0)로 고정한다.
                () -> assertEquals("0", args.get(12))
        );
    }

    @Test
    void sharedRefillRetryUsesResidualAndReturnsAccumulatedAnswer() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficRedisKeyFactory.sharedRefillLockKey(22L)).thenReturn("pooli:shared_refill_lock:22");
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);

        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                .thenReturn(luaResult(41L, TrafficLuaStatus.NO_BALANCE))
                .thenReturn(luaResult(9L, TrafficLuaStatus.OK));
        when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_shared_amount:22:202603", 0L)).thenReturn(0L);
        when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.SHARED, payload))
                .thenReturn(refillPlan(5L, 2, 10L, 50L, 15L, "RECENT_10S"));
        when(trafficLuaScriptInfraService.executeRefillGate(
                "pooli:shared_refill_lock:22",
                "pooli:remaining_shared_amount:22:202603",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                0L,
                15L
        )).thenReturn(TrafficRefillGateStatus.OK);
        when(trafficLuaScriptInfraService.executeLockHeartbeat(
                "pooli:shared_refill_lock:22",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS
        )).thenReturn(true, true);
        when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                eq(TrafficPoolType.SHARED),
                eq(payload),
                eq(java.time.YearMonth.of(2026, 3)),
                eq(50L),
                anyString()
        )).thenReturn(claimResult(50L, 50L, 50L, 0L));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 50L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                () -> assertEquals(50L, result.getAnswer())
        );
        // writeDbEmptyFlag는 더 이상 별도 호출되지 않는다 — Lua 내부에서 원자적으로 처리된다.
        verify(trafficQuotaCacheService, never()).writeDbEmptyFlag(anyString(), anyBoolean());
        verify(trafficQuotaCacheService, never()).applyRefillWithIdempotency(
                anyString(),
                anyString(),
                anyString(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyBoolean()
        );
        verify(trafficLuaScriptInfraService).executeLockRelease("pooli:shared_refill_lock:22", payload.getTraceId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService, times(2)).executeDeductShared(anyList(), argsCaptor.capture());
        List<List<String>> allArgs = argsCaptor.getAllValues();
        assertAll(
                () -> assertEquals("50", allArgs.get(0).get(0)),
                () -> assertEquals("9", allArgs.get(1).get(0)),
                () -> assertEquals("0", allArgs.get(0).get(12)),
                () -> assertEquals("0", allArgs.get(1).get(12))
        );
    }

    @Test
    void sharedDbRefillNoopThenAllowsSingleQosFallback() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficRedisKeyFactory.sharedRefillLockKey(22L)).thenReturn("pooli:shared_refill_lock:22");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                // 1차 shared 차감: 부족 상태를 열어 DB 리필 시도로 진입한다.
                .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                // DB 리필 시도 후 마지막 1회 QOS fallback 호출
                .thenReturn(luaResult(50L, TrafficLuaStatus.QOS));
        when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_shared_amount:22:202603", 0L)).thenReturn(0L);
        when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.SHARED, payload))
                .thenReturn(refillPlan(5L, 2, 10L, 50L, 15L, "RECENT_10S"));
        when(trafficLuaScriptInfraService.executeRefillGate(
                "pooli:shared_refill_lock:22",
                "pooli:remaining_shared_amount:22:202603",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                0L,
                15L
        )).thenReturn(TrafficRefillGateStatus.OK);
        when(trafficLuaScriptInfraService.executeLockHeartbeat(
                "pooli:shared_refill_lock:22",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS
        )).thenReturn(true);
        when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                eq(TrafficPoolType.SHARED),
                eq(payload),
                eq(java.time.YearMonth.of(2026, 3)),
                eq(50L),
                anyString()
        )).thenReturn(claimResult(50L, 0L, 0L, 0L));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 50L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.QOS, result.getStatus()),
                () -> assertEquals(50L, result.getAnswer())
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService, times(2)).executeDeductShared(anyList(), argsCaptor.capture());
        List<List<String>> allArgs = argsCaptor.getAllValues();
        assertAll(
                // 초기/리필 이전 shared 호출은 QOS fallback 비활성
                () -> assertEquals("0", allArgs.get(0).get(12)),
                // DB 리필 시도 이후 마지막 호출에서만 QOS fallback 허용
                () -> assertEquals("1", allArgs.get(1).get(12))
        );
    }

    @Test
    void sharedGateSkipDbEmptyAllowsSingleQosFallback() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficRedisKeyFactory.sharedRefillLockKey(22L)).thenReturn("pooli:shared_refill_lock:22");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                // 1차 shared 차감: 부족 상태를 열어 refill gate 평가로 진입한다.
                .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                // gate에서 DB_EMPTY로 스킵된 뒤 QoS fallback 1회 호출
                .thenReturn(luaResult(50L, TrafficLuaStatus.QOS));
        when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_shared_amount:22:202603", 0L)).thenReturn(0L);
        when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.SHARED, payload))
                .thenReturn(refillPlan(5L, 2, 10L, 50L, 15L, "RECENT_10S"));
        when(trafficLuaScriptInfraService.executeRefillGate(
                "pooli:shared_refill_lock:22",
                "pooli:remaining_shared_amount:22:202603",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                0L,
                15L
        )).thenReturn(TrafficRefillGateStatus.SKIP_DB_EMPTY);

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 50L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.QOS, result.getStatus()),
                () -> assertEquals(50L, result.getAnswer())
        );
        verify(trafficQuotaSourcePort, never()).claimRefillAmountFromDb(any(), any(), any(), anyLong(), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService, times(2)).executeDeductShared(anyList(), argsCaptor.capture());
        List<List<String>> allArgs = argsCaptor.getAllValues();
        assertAll(
                // 초기 shared 호출은 QOS fallback 비활성
                () -> assertEquals("0", allArgs.get(0).get(12)),
                // gate SKIP_DB_EMPTY 이후 fallback 호출에서만 QOS 허용
                () -> assertEquals("1", allArgs.get(1).get(12))
        );
    }

    @Test
    void sharedGateSkipThresholdKeepsNoBalanceWithoutQosFallback() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficRedisKeyFactory.sharedRefillLockKey(22L)).thenReturn("pooli:shared_refill_lock:22");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
        when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_shared_amount:22:202603", 0L)).thenReturn(0L);
        when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.SHARED, payload))
                .thenReturn(refillPlan(5L, 2, 10L, 50L, 15L, "RECENT_10S"));
        when(trafficLuaScriptInfraService.executeRefillGate(
                "pooli:shared_refill_lock:22",
                "pooli:remaining_shared_amount:22:202603",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                0L,
                15L
        )).thenReturn(TrafficRefillGateStatus.SKIP_THRESHOLD);

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 50L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                () -> assertEquals(0L, result.getAnswer())
        );
        verify(trafficQuotaSourcePort, never()).claimRefillAmountFromDb(any(), any(), any(), anyLong(), anyString());
        verify(trafficLuaScriptInfraService, times(1)).executeDeductShared(anyList(), anyList());
    }

    @Test
    void sharedDbNoopQosFallbackKeepsFirstSharedDeduction() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficRedisKeyFactory.sharedRefillLockKey(22L)).thenReturn("pooli:shared_refill_lock:22");
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                // 1차 shared 차감에서 일부(10)만 성공하고 부족 상태를 반환한다.
                .thenReturn(luaResult(10L, TrafficLuaStatus.NO_BALANCE))
                // DB 리필 No-Op 이후 QOS fallback으로 부족분(30)을 보정한다.
                .thenReturn(luaResult(30L, TrafficLuaStatus.QOS));
        when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_shared_amount:22:202603", 0L)).thenReturn(0L);
        when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.SHARED, payload))
                .thenReturn(refillPlan(5L, 2, 10L, 50L, 15L, "RECENT_10S"));
        when(trafficLuaScriptInfraService.executeRefillGate(
                "pooli:shared_refill_lock:22",
                "pooli:remaining_shared_amount:22:202603",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                0L,
                15L
        )).thenReturn(TrafficRefillGateStatus.OK);
        when(trafficLuaScriptInfraService.executeLockHeartbeat(
                "pooli:shared_refill_lock:22",
                payload.getTraceId(),
                TrafficRedisRuntimePolicy.LOCK_TTL_MS
        )).thenReturn(true);
        when(trafficQuotaSourcePort.claimRefillAmountFromDb(
                eq(TrafficPoolType.SHARED),
                eq(payload),
                eq(java.time.YearMonth.of(2026, 3)),
                eq(50L),
                anyString()
        )).thenReturn(claimResult(10L, 0L, 0L, 0L));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 40L);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.QOS, result.getStatus()),
                // 1차 shared(10) + QOS fallback(30)이 합산되어 총 40이 되어야 한다.
                () -> assertEquals(40L, result.getAnswer())
        );
    }

    @Test
    @DisplayName("동일 traceId 컨텍스트에서는 차단성 정책 검증을 1회만 수행하고 공유 경로에서 재사용한다")
    void reusesBlockingPolicyCheckResultAcrossIndividualAndSharedWithSameContext() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        TrafficDeductExecutionContext context = TrafficDeductExecutionContext.of(payload.getTraceId());
        stubIndividualDeductKeys();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficLuaScriptInfraService.executeDeductIndividual(anyList(), anyList()))
                .thenReturn(luaResult(10L, TrafficLuaStatus.OK));
        when(trafficLuaScriptInfraService.executeDeductShared(anyList(), anyList()))
                .thenReturn(luaResult(5L, TrafficLuaStatus.OK));

        // when
        TrafficLuaExecutionResult individualResult =
                trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L, context);
        TrafficLuaExecutionResult sharedResult =
                trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 50L, context);

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, individualResult.getStatus()),
                () -> assertEquals(10L, individualResult.getAnswer()),
                () -> assertEquals(TrafficLuaStatus.OK, sharedResult.getStatus()),
                () -> assertEquals(5L, sharedResult.getAnswer())
        );
        verify(trafficLuaScriptInfraService, times(1)).executeDeductIndividual(anyList(), anyList());
        verify(trafficLuaScriptInfraService, times(1)).executeDeductShared(anyList(), anyList());
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

    private void stubIndividualDeductKeys() {
        for (long policyId = 1; policyId <= 7; policyId++) {
            when(trafficRedisKeyFactory.policyKey(policyId)).thenReturn("pooli:policy:" + policyId);
        }
        when(trafficRedisKeyFactory.appWhitelistKey(11L)).thenReturn("pooli:app_whitelist:11");
        when(trafficRedisKeyFactory.immediatelyBlockEndKey(11L)).thenReturn("pooli:immediately_block_end:11");
        when(trafficRedisKeyFactory.repeatBlockKey(11L)).thenReturn("pooli:repeat_block:11");
        when(trafficRedisKeyFactory.dailyTotalLimitKey(11L)).thenReturn("pooli:daily_total_limit:11");
        Mockito.lenient().when(trafficRedisKeyFactory.dailyTotalUsageKey(eq(11L), any()))
                .thenReturn("pooli:daily_total_usage:11:20260312");
        when(trafficRedisKeyFactory.appDataDailyLimitKey(11L)).thenReturn("pooli:app_data_daily_limit:11");
        Mockito.lenient().when(trafficRedisKeyFactory.dailyAppUsageKey(eq(11L), any()))
                .thenReturn("pooli:daily_app_usage:11:20260312");
        when(trafficRedisKeyFactory.appSpeedLimitKey(11L)).thenReturn("pooli:app_speed_limit:11");
        Mockito.lenient().when(trafficRedisKeyFactory.speedBucketIndividualAppKey(eq(11L), eq(33), anyLong()))
                .thenReturn("pooli:speed_bucket:individual:11:33:1");
        when(trafficRedisKeyFactory.dedupeRunKey("trace-001")).thenReturn("pooli:dedupe:run:trace-001");
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(any())).thenReturn(1_741_800_000L);
    }

    private void stubSharedDeductKeys() {
        for (long policyId = 1; policyId <= 7; policyId++) {
            when(trafficRedisKeyFactory.policyKey(policyId)).thenReturn("pooli:policy:" + policyId);
        }
        Mockito.lenient().when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any()))
                .thenReturn("pooli:remaining_indiv_amount:11:202603");
        when(trafficRedisKeyFactory.appWhitelistKey(11L)).thenReturn("pooli:app_whitelist:11");
        when(trafficRedisKeyFactory.immediatelyBlockEndKey(11L)).thenReturn("pooli:immediately_block_end:11");
        when(trafficRedisKeyFactory.repeatBlockKey(11L)).thenReturn("pooli:repeat_block:11");
        when(trafficRedisKeyFactory.dailyTotalLimitKey(11L)).thenReturn("pooli:daily_total_limit:11");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(eq(11L), any())).thenReturn("pooli:daily_total_usage:11:20260312");
        when(trafficRedisKeyFactory.monthlySharedLimitKey(11L)).thenReturn("pooli:monthly_shared_limit:11");
        when(trafficRedisKeyFactory.monthlySharedUsageKey(eq(11L), any())).thenReturn("pooli:monthly_shared_usage:11:202603");
        when(trafficRedisKeyFactory.appDataDailyLimitKey(11L)).thenReturn("pooli:app_data_daily_limit:11");
        when(trafficRedisKeyFactory.dailyAppUsageKey(eq(11L), any())).thenReturn("pooli:daily_app_usage:11:20260312");
        when(trafficRedisKeyFactory.appSpeedLimitKey(11L)).thenReturn("pooli:app_speed_limit:11");
        when(trafficRedisKeyFactory.speedBucketIndividualAppKey(eq(11L), eq(33), anyLong()))
                .thenReturn("pooli:speed_bucket:individual:11:33:1");
        when(trafficRedisKeyFactory.dedupeRunKey("trace-001")).thenReturn("pooli:dedupe:run:trace-001");
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(any())).thenReturn(1_741_800_000L);
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_742_700_000L);
    }

    private TrafficLuaExecutionResult luaResult(long answer, TrafficLuaStatus status) {
        return TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build();
    }

    private TrafficDbRefillClaimResult claimResult(
            long requestedRefillAmount,
            long dbRemainingBefore,
            long actualRefillAmount,
            long dbRemainingAfter
    ) {
        String refillUuid = "refill-uuid-" + requestedRefillAmount + "-" + actualRefillAmount;
        return TrafficDbRefillClaimResult.builder()
                .requestedRefillAmount(requestedRefillAmount)
                .dbRemainingBefore(dbRemainingBefore)
                .actualRefillAmount(actualRefillAmount)
                .dbRemainingAfter(dbRemainingAfter)
                .refillUuid(refillUuid)
                .outboxRecordId(701L)
                .build();
    }

    private TrafficRefillPlan refillPlan(
            long delta,
            int bucketCount,
            long bucketSum,
            long refillUnit,
            long threshold,
            String source
    ) {
        return TrafficRefillPlan.builder()
                .delta(delta)
                .bucketCount(bucketCount)
                .bucketSum(bucketSum)
                .refillUnit(refillUnit)
                .threshold(threshold)
                .source(source)
                .build();
    }
}
