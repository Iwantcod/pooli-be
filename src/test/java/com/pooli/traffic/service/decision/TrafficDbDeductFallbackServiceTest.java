package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.PolicyActivationSnapshotResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.mapper.AppPolicyMapper;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.LineLimitMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficDbUsageMapper;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrafficDbDeductFallbackServiceTest {

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private PolicyBackOfficeMapper policyBackOfficeMapper;

    @Mock
    private LineLimitMapper lineLimitMapper;

    @Mock
    private AppPolicyMapper appPolicyMapper;

    @Mock
    private ImmediateBlockMapper immediateBlockMapper;

    @Mock
    private RepeatBlockMapper repeatBlockMapper;

    @Mock
    private TrafficRefillSourceMapper trafficRefillSourceMapper;

    @Mock
    private TrafficDbUsageMapper trafficDbUsageMapper;

    @InjectMocks
    private TrafficDbDeductFallbackService trafficDbDeductFallbackService;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        Mockito.lenient().when(repeatBlockMapper.selectRepeatBlocksByLineId(anyLong())).thenReturn(List.of());
        Mockito.lenient().when(lineLimitMapper.getExistLineLimitByLineId(anyLong())).thenReturn(Optional.empty());
        Mockito.lenient().when(appPolicyMapper.findEntityExistByLineIdAndAppId(anyLong(), anyInt())).thenReturn(Optional.empty());
        Mockito.lenient().when(immediateBlockMapper.selectImmediateBlockPolicy(anyLong())).thenReturn(null);
    }

    @Test
    @DisplayName("즉시 차단 정책이 활성화되면 BLOCKED_IMMEDIATE를 반환한다")
    void returnsBlockedImmediateWhenImmediatePolicyIsActiveAndInWindow() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(true, false, false, false, false, false, false));
        when(immediateBlockMapper.selectImmediateBlockPolicy(11L))
                .thenReturn(ImmediateBlockResDto.builder()
                        .lineId(11L)
                        .blockEndAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(5))
                        .build());

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                100L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, result.getStatus()),
                () -> assertEquals(0L, result.getAnswer())
        );
        verify(trafficRefillSourceMapper, never()).selectIndividualRemainingForUpdate(anyLong());
        verify(trafficDbUsageMapper, never()).upsertDailyTotalUsage(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("앱 일일 제한 정책이 활성화되면 해당 제한까지 차감되고 HIT_APP_DAILY_LIMIT를 반환한다")
    void returnsHitAppDailyLimitWhenAppDailyPolicyIsActive() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, true, false, false));
        when(appPolicyMapper.findEntityExistByLineIdAndAppId(11L, 33))
                .thenReturn(Optional.of(AppPolicy.builder()
                        .lineId(11L)
                        .applicationId(33)
                        .isActive(true)
                        .dataLimit(30L)
                        .isWhitelist(false)
                        .build()));
        when(trafficRefillSourceMapper.selectIndividualRemainingForUpdate(11L)).thenReturn(100L);
        when(trafficDbUsageMapper.selectDailyAppUsage(eq(11L), eq(33), any())).thenReturn(0L);
        when(trafficRefillSourceMapper.deductIndividualRemaining(11L, 30L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                50L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.HIT_APP_DAILY_LIMIT, result.getStatus()),
                () -> assertEquals(30L, result.getAnswer())
        );
        verify(trafficDbUsageMapper).upsertDailyTotalUsage(eq(11L), any(), eq(30L));
        verify(trafficDbUsageMapper).upsertDailyAppUsage(eq(11L), eq(33), any(), eq(30L));
    }

    @Test
    @DisplayName("DB fallback 차감 성공 시 사용량 집계를 갱신한다")
    void updatesUsageWhenFallbackDeductSucceeds() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, false, false));
        when(trafficRefillSourceMapper.selectIndividualRemainingForUpdate(11L)).thenReturn(100L);
        when(trafficRefillSourceMapper.deductIndividualRemaining(11L, 40L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                40L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                () -> assertEquals(40L, result.getAnswer())
        );
        verify(trafficDbUsageMapper).upsertDailyTotalUsage(eq(11L), any(), eq(40L));
        verify(trafficDbUsageMapper).upsertDailyAppUsage(eq(11L), eq(33), any(), eq(40L));
    }

    @Test
    @DisplayName("공유풀 fallback 차감 성공 시 가족 공유 사용량 집계를 함께 갱신한다")
    void updatesFamilySharedUsageWhenSharedFallbackDeductSucceeds() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, false, false));
        when(trafficRefillSourceMapper.selectSharedRemainingForUpdate(22L)).thenReturn(80L);
        when(trafficRefillSourceMapper.deductSharedRemaining(22L, 30L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.SHARED,
                payload,
                30L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                () -> assertEquals(30L, result.getAnswer())
        );
        verify(trafficDbUsageMapper).upsertDailyTotalUsage(eq(11L), any(), eq(30L));
        verify(trafficDbUsageMapper).upsertDailyAppUsage(eq(11L), eq(33), any(), eq(30L));
        verify(trafficDbUsageMapper).upsertFamilySharedUsageDaily(eq(22L), eq(11L), any(), eq(30L));
    }

    @Test
    @DisplayName("자정 넘김 repeat block은 당일 밤/익일 새벽 구간을 모두 차단한다")
    void blocksCrossMidnightRangeForTodayAndPreviousDay() {
        // given
        RepeatBlockDayResDto crossMidnight = RepeatBlockDayResDto.builder()
                .dayOfWeek(DayOfWeek.MON)
                .startAt(LocalTime.of(22, 0, 0))
                .endAt(LocalTime.of(7, 0, 0))
                .build();
        RepeatBlockPolicyResDto repeatBlock = RepeatBlockPolicyResDto.builder()
                .repeatBlockId(300L)
                .lineId(11L)
                .isActive(true)
                .days(List.of(crossMidnight))
                .build();
        when(repeatBlockMapper.selectRepeatBlocksByLineId(11L)).thenReturn(List.of(repeatBlock));

        // when
        boolean blockedAtTodayNight = invokeIsRepeatBlocked(11L, LocalTime.of(23, 59, 59), DayOfWeek.MON, DayOfWeek.SUN);
        boolean blockedAtNextDayEarlyMorning = invokeIsRepeatBlocked(11L, LocalTime.of(0, 0, 0), DayOfWeek.TUE, DayOfWeek.MON);
        boolean notBlockedAtNextDayNoon = invokeIsRepeatBlocked(11L, LocalTime.NOON, DayOfWeek.TUE, DayOfWeek.MON);

        // then
        assertAll(
                () -> assertTrue(blockedAtTodayNight),
                () -> assertTrue(blockedAtNextDayEarlyMorning),
                () -> assertFalse(notBlockedAtNextDayNoon)
        );
    }

    @Test
    @DisplayName("same-day repeat block은 기존 범위 비교를 유지한다")
    void keepsSameDayRangeLogic() {
        // given
        RepeatBlockDayResDto sameDay = RepeatBlockDayResDto.builder()
                .dayOfWeek(DayOfWeek.WED)
                .startAt(LocalTime.of(9, 0, 0))
                .endAt(LocalTime.of(18, 0, 0))
                .build();
        RepeatBlockPolicyResDto repeatBlock = RepeatBlockPolicyResDto.builder()
                .repeatBlockId(301L)
                .lineId(11L)
                .isActive(true)
                .days(List.of(sameDay))
                .build();
        when(repeatBlockMapper.selectRepeatBlocksByLineId(11L)).thenReturn(List.of(repeatBlock));

        // when
        boolean blockedInRange = invokeIsRepeatBlocked(11L, LocalTime.of(9, 0, 0), DayOfWeek.WED, DayOfWeek.TUE);
        boolean blockedAtEndBoundary = invokeIsRepeatBlocked(11L, LocalTime.of(18, 0, 0), DayOfWeek.WED, DayOfWeek.TUE);
        boolean notBlockedOutOfRange = invokeIsRepeatBlocked(11L, LocalTime.of(18, 0, 1), DayOfWeek.WED, DayOfWeek.TUE);

        // then
        assertAll(
                () -> assertTrue(blockedInRange),
                () -> assertTrue(blockedAtEndBoundary),
                () -> assertFalse(notBlockedOutOfRange)
        );
    }

    private TrafficPayloadReqDto payload() {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(System.currentTimeMillis())
                .build();
    }

    private List<PolicyActivationSnapshotResDto> policySnapshot(
            boolean immediate,
            boolean repeat,
            boolean sharedMonthly,
            boolean daily,
            boolean appDaily,
            boolean appSpeed,
            boolean appWhitelist
    ) {
        List<PolicyActivationSnapshotResDto> snapshots = new ArrayList<>();
        snapshots.add(policy(1, repeat));
        snapshots.add(policy(2, immediate));
        snapshots.add(policy(3, sharedMonthly));
        snapshots.add(policy(4, daily));
        snapshots.add(policy(5, appDaily));
        snapshots.add(policy(6, appSpeed));
        snapshots.add(policy(7, appWhitelist));
        return snapshots;
    }

    private PolicyActivationSnapshotResDto policy(int policyId, boolean active) {
        return PolicyActivationSnapshotResDto.builder()
                .policyId(policyId)
                .isActive(active)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private boolean invokeIsRepeatBlocked(
            Long lineId,
            LocalTime nowTime,
            DayOfWeek nowDayOfWeek,
            DayOfWeek yesterdayDayOfWeek
    ) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(
                        trafficDbDeductFallbackService,
                        "isRepeatBlocked",
                        lineId,
                        nowTime,
                        nowDayOfWeek,
                        yesterdayDayOfWeek
                )
        );
    }
}
