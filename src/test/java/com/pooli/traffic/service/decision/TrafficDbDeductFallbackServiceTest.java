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
import com.pooli.traffic.mapper.TrafficDbSpeedBucketMapper;
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

    @Mock
    private TrafficDbSpeedBucketMapper trafficDbSpeedBucketMapper;

    @Mock
    private TrafficUsageDeltaRecordService trafficUsageDeltaRecordService;

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
    @DisplayName("AppSpeed 정책 초과 시 HIT_APP_SPEED를 반환한다")
    void returnsHitAppSpeedWhenRecentUsageReachedLimit() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, true, false));
        when(appPolicyMapper.findEntityExistByLineIdAndAppId(11L, 33))
                .thenReturn(Optional.of(AppPolicy.builder()
                        .lineId(11L)
                        .applicationId(33)
                        .isActive(true)
                        .speedLimit(1)
                        .isWhitelist(false)
                        .build()));
        when(trafficRefillSourceMapper.selectIndividualRemainingForUpdate(11L)).thenReturn(100L);
        when(trafficDbSpeedBucketMapper.selectRecentUsageSum(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                anyLong()
        )).thenReturn(125L);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                50L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.HIT_APP_SPEED, result.getStatus()),
                () -> assertEquals(0L, result.getAnswer())
        );
        verify(trafficRefillSourceMapper, never()).deductIndividualRemaining(anyLong(), anyLong());
        verify(trafficDbUsageMapper, never()).upsertDailyTotalUsage(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("AppSpeed로 cap된 목표량보다 잔량이 작으면 부분 차감 후 NO_BALANCE를 반환한다")
    void returnsNoBalanceWhenAppSpeedCappedButBalanceIsInsufficient() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, true, false));
        when(appPolicyMapper.findEntityExistByLineIdAndAppId(11L, 33))
                .thenReturn(Optional.of(AppPolicy.builder()
                        .lineId(11L)
                        .applicationId(33)
                        .isActive(true)
                        .speedLimit(1)
                        .isWhitelist(false)
                        .build()));
        when(trafficRefillSourceMapper.selectIndividualRemainingForUpdate(11L)).thenReturn(50L);
        when(trafficDbSpeedBucketMapper.selectRecentUsageSum(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                anyLong()
        )).thenReturn(65L);
        when(trafficRefillSourceMapper.deductIndividualRemaining(11L, 50L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                100L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.NO_BALANCE, result.getStatus()),
                () -> assertEquals(50L, result.getAnswer())
        );
        verify(trafficRefillSourceMapper).deductIndividualRemaining(11L, 50L);
        verify(trafficDbUsageMapper).upsertDailyTotalUsage(eq(11L), any(), eq(50L));
        verify(trafficDbUsageMapper).upsertDailyAppUsage(eq(11L), eq(33), any(), eq(50L));
    }

    @Test
    @DisplayName("AppSpeed로 cap되어도 차감이 성공하면 최종 상태는 HIT_APP_SPEED다")
    void returnsHitAppSpeedWhenSpeedCapAppliedAndDeductSucceeded() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, true, false));
        when(appPolicyMapper.findEntityExistByLineIdAndAppId(11L, 33))
                .thenReturn(Optional.of(AppPolicy.builder()
                        .lineId(11L)
                        .applicationId(33)
                        .isActive(true)
                        .speedLimit(1)
                        .isWhitelist(false)
                        .build()));
        when(trafficRefillSourceMapper.selectIndividualRemainingForUpdate(11L)).thenReturn(200L);
        when(trafficDbSpeedBucketMapper.selectRecentUsageSum(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                anyLong()
        )).thenReturn(65L);
        when(trafficRefillSourceMapper.deductIndividualRemaining(11L, 60L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.INDIVIDUAL,
                payload,
                100L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.HIT_APP_SPEED, result.getStatus()),
                () -> assertEquals(60L, result.getAnswer())
        );
        verify(trafficRefillSourceMapper).deductIndividualRemaining(11L, 60L);
        verify(trafficDbUsageMapper).upsertDailyTotalUsage(eq(11L), any(), eq(60L));
        verify(trafficDbUsageMapper).upsertDailyAppUsage(eq(11L), eq(33), any(), eq(60L));
    }

    @Test
    @DisplayName("DB fallback 차감 성공 시 집계와 usage delta를 함께 반영한다")
    void updatesUsageAndRecordsDeltaWhenFallbackDeductSucceeds() {
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
        verify(trafficDbSpeedBucketMapper).upsertUsage(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                eq(40L)
        );
        verify(trafficUsageDeltaRecordService).record(
                eq(payload.getTraceId()),
                eq(TrafficPoolType.INDIVIDUAL),
                eq(11L),
                eq(22L),
                eq(33),
                eq(40L),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("공유풀 차감이어도 앱 속도 버킷은 lineId 기준으로 조회/적재한다")
    void sharedPoolUsesLineScopedAppSpeedBucket() {
        // given
        TrafficPayloadReqDto payload = payload();
        when(policyBackOfficeMapper.selectPolicyActivationSnapshot())
                .thenReturn(policySnapshot(false, false, false, false, false, true, false));
        when(appPolicyMapper.findEntityExistByLineIdAndAppId(11L, 33))
                .thenReturn(Optional.of(AppPolicy.builder()
                        .lineId(11L)
                        .applicationId(33)
                        .isActive(true)
                        .speedLimit(1)
                        .isWhitelist(false)
                        .build()));
        when(trafficRefillSourceMapper.selectSharedRemainingForUpdate(22L)).thenReturn(100L);
        when(trafficDbSpeedBucketMapper.selectRecentUsageSum(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                anyLong()
        )).thenReturn(0L);
        when(trafficRefillSourceMapper.deductSharedRemaining(22L, 40L)).thenReturn(1);

        // when
        TrafficLuaExecutionResult result = trafficDbDeductFallbackService.deduct(
                TrafficPoolType.SHARED,
                payload,
                40L,
                TrafficDeductExecutionContext.of(payload.getTraceId())
        );

        // then
        assertAll(
                () -> assertEquals(TrafficLuaStatus.OK, result.getStatus()),
                () -> assertEquals(40L, result.getAnswer())
        );
        verify(trafficDbSpeedBucketMapper).selectRecentUsageSum(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                anyLong()
        );
        verify(trafficDbSpeedBucketMapper).upsertUsage(
                eq(TrafficPoolType.INDIVIDUAL.name()),
                eq(11L),
                eq(33),
                anyLong(),
                eq(40L)
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
}
