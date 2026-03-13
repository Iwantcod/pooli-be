package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.pooli.traffic.service.policy.TrafficLinePolicyHydrationService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.monitoring.metrics.TrafficRefillMetrics;
import com.pooli.traffic.domain.TrafficDbRefillClaimResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.domain.enums.TrafficRefillGateStatus;

@ExtendWith(MockitoExtension.class)
class TrafficHydrateRefillAdapterServiceTest {

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
    private TrafficLinePolicyHydrationService trafficLinePolicyHydrationService;

    @Mock
    private TrafficHydrateMetrics trafficHydrateMetrics;

    @Mock
    private TrafficRefillMetrics trafficRefillMetrics;

    @InjectMocks
    private TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;

    @Nested
    @DisplayName("executeIndividualWithRecovery 테스트")
    class ExecuteIndividualWithRecoveryTest {

        @Test
        @DisplayName("HYDRATE 발생 시 hydrate 후 1회 재시도하여 OK 반환")
        void hydrateThenRetrySuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(trafficLuaScriptInfraService.executeDeductIndivTick(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.HYDRATE))
                    .thenReturn(luaResult(80L, TrafficLuaStatus.OK));
            when(trafficQuotaSourcePort.loadInitialAmount(TrafficPoolType.INDIVIDUAL, payload, java.time.YearMonth.of(2026, 3)))
                    .thenReturn(200L);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(80L, result.getAnswer())
            );
            verify(trafficQuotaCacheService).hydrateBalance("pooli:remaining_indiv_amount:11:202603", 200L, 1_770_000_000L);
        }

        @Test
        @DisplayName("NO_BALANCE + gate OK 시 refill 후 1회 재시도")
        void refillGateOkThenRetrySuccess() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);

            when(trafficLuaScriptInfraService.executeDeductIndivTick(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                    .thenReturn(luaResult(60L, TrafficLuaStatus.OK));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
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
                    TrafficPoolType.INDIVIDUAL,
                    payload,
                    java.time.YearMonth.of(2026, 3),
                    100L
            )).thenReturn(claimResult(100L, 1_000L, 100L, 900L));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                    () -> assertEquals(60L, result.getAnswer())
            );
            verify(trafficQuotaCacheService).refillBalance("pooli:remaining_indiv_amount:11:202603", 100L, 1_770_000_000L);
            verify(trafficLuaScriptInfraService).executeLockRelease("pooli:indiv_refill_lock:11", payload.getTraceId());
        }

        @Test
        @DisplayName("NO_BALANCE + gate WAIT 시 기존 결과 유지")
        void refillGateWaitKeepsNoBalance() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");

            when(trafficLuaScriptInfraService.executeDeductIndivTick(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillPlan(TrafficPoolType.INDIVIDUAL, payload))
                    .thenReturn(refillPlan(10L, 2, 20L, 100L, 30L, "RECENT_10S"));
            when(trafficLuaScriptInfraService.executeRefillGate(
                    "pooli:indiv_refill_lock:11",
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
        @DisplayName("payload 필수값 누락 시 ERROR 반환")
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
        @DisplayName("차감 Lua 실행 전에 line 정책 ensureLoaded를 먼저 호출한다")
        void ensuresLinePolicyBeforeDeduct() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndivTick(anyList(), anyList()))
                    .thenReturn(luaResult(1L, TrafficLuaStatus.OK));

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertEquals(TrafficLuaStatus.OK, result.getStatus());
            InOrder inOrder = inOrder(trafficLinePolicyHydrationService, trafficLuaScriptInfraService);
            inOrder.verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
            inOrder.verify(trafficLuaScriptInfraService).executeDeductIndivTick(anyList(), anyList());
        }

        @Test
        @DisplayName("line 정책 ensureLoaded 실패 시 ERROR 반환하고 Lua는 호출하지 않는다")
        void returnsErrorWhenLinePolicyHydrationFails() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            doThrow(new RuntimeException("hydrate-fail"))
                    .when(trafficLinePolicyHydrationService)
                    .ensureLoaded(11L);

            // when
            TrafficLuaExecutionResult result =
                    trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertAll(
                    () -> assertEquals(TrafficLuaStatus.ERROR, result.getStatus()),
                    () -> assertEquals(-1L, result.getAnswer())
            );
            verify(trafficLuaScriptInfraService, never()).executeDeductIndivTick(anyList(), anyList());
        }

        @Test
        @DisplayName("개인풀 차감 시 전역 정책 key(1~7)를 Lua KEYS에 포함한다")
        void includesPolicyKeysForIndividualDeduct() {
            // given
            TrafficPayloadReqDto payload = createPayload();
            stubIndividualDeductKeys();
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficLuaScriptInfraService.executeDeductIndivTick(anyList(), anyList()))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE));

            // when
            TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, 100L);

            // then
            assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getStatus());
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            verify(trafficLuaScriptInfraService).executeDeductIndivTick(keysCaptor.capture(), anyList());
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
    }

    @Test
    @DisplayName("공유풀 차감 시 전역 정책 key와 월 공유 한도 key를 Lua KEYS에 포함한다")
    void includesPolicyAndMonthlyKeysForSharedDeduct() {
        // given
        TrafficPayloadReqDto payload = createPayload();
        stubSharedDeductKeys();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(eq(22L), any())).thenReturn("pooli:remaining_shared_amount:22:202603");
        when(trafficLuaScriptInfraService.executeDeductSharedTick(anyList(), anyList()))
                .thenReturn(luaResult(0L, TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT));

        // when
        TrafficLuaExecutionResult result = trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, 100L);

        // then
        assertEquals(TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT, result.getStatus());
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService).executeDeductSharedTick(keysCaptor.capture(), anyList());
        List<String> keys = keysCaptor.getValue();
        assertAll(
                () -> assertTrue(keys.contains("pooli:policy:1")),
                () -> assertTrue(keys.contains("pooli:policy:2")),
                () -> assertTrue(keys.contains("pooli:policy:3")),
                () -> assertTrue(keys.contains("pooli:policy:4")),
                () -> assertTrue(keys.contains("pooli:policy:5")),
                () -> assertTrue(keys.contains("pooli:policy:6")),
                () -> assertTrue(keys.contains("pooli:policy:7")),
                () -> assertTrue(keys.contains("pooli:monthly_shared_limit:11")),
                () -> assertTrue(keys.contains("pooli:monthly_shared_usage:11:202603"))
        );
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
        when(trafficRedisKeyFactory.dailyTotalUsageKey(eq(11L), any())).thenReturn("pooli:daily_total_usage:11:20260312");
        when(trafficRedisKeyFactory.appDataDailyLimitKey(11L)).thenReturn("pooli:app_data_daily_limit:11");
        when(trafficRedisKeyFactory.dailyAppUsageKey(eq(11L), any())).thenReturn("pooli:daily_app_usage:11:20260312");
        when(trafficRedisKeyFactory.appSpeedLimitKey(11L)).thenReturn("pooli:app_speed_limit:11");
        when(trafficRedisKeyFactory.speedBucketIndividualKey(eq(11L), anyLong())).thenReturn("pooli:speed_bucket:individual:11:1");
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(any())).thenReturn(1_741_800_000L);
    }

    private void stubSharedDeductKeys() {
        for (long policyId = 1; policyId <= 7; policyId++) {
            when(trafficRedisKeyFactory.policyKey(policyId)).thenReturn("pooli:policy:" + policyId);
        }
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
        when(trafficRedisKeyFactory.speedBucketSharedKey(eq(22L), anyLong())).thenReturn("pooli:speed_bucket:shared:22:1");
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
        return TrafficDbRefillClaimResult.builder()
                .requestedRefillAmount(requestedRefillAmount)
                .dbRemainingBefore(dbRemainingBefore)
                .actualRefillAmount(actualRefillAmount)
                .dbRemainingAfter(dbRemainingAfter)
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
