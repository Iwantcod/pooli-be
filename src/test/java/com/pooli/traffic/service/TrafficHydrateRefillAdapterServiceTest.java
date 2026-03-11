package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;
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
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);
            when(trafficLuaScriptInfraService.executeDeductIndivTick("pooli:remaining_indiv_amount:11:202603", 100L))
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
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");
            when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(any())).thenReturn(1_770_000_000L);

            when(trafficLuaScriptInfraService.executeDeductIndivTick("pooli:remaining_indiv_amount:11:202603", 100L))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE))
                    .thenReturn(luaResult(60L, TrafficLuaStatus.OK));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillThreshold(TrafficPoolType.INDIVIDUAL, payload)).thenReturn(30L);
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
            when(trafficQuotaSourcePort.resolveRefillUnit(TrafficPoolType.INDIVIDUAL, payload)).thenReturn(100L);

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
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
            when(trafficRedisKeyFactory.remainingIndivAmountKey(eq(11L), any())).thenReturn("pooli:remaining_indiv_amount:11:202603");
            when(trafficRedisKeyFactory.indivRefillLockKey(11L)).thenReturn("pooli:indiv_refill_lock:11");

            when(trafficLuaScriptInfraService.executeDeductIndivTick("pooli:remaining_indiv_amount:11:202603", 100L))
                    .thenReturn(luaResult(0L, TrafficLuaStatus.NO_BALANCE));
            when(trafficQuotaCacheService.readAmountOrDefault("pooli:remaining_indiv_amount:11:202603", 0L)).thenReturn(0L);
            when(trafficQuotaSourcePort.resolveRefillThreshold(TrafficPoolType.INDIVIDUAL, payload)).thenReturn(30L);
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
