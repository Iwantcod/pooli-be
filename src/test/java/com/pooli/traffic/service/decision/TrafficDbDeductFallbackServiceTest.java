package com.pooli.traffic.service.decision;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import com.pooli.policy.domain.entity.AppPolicy;
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
}
