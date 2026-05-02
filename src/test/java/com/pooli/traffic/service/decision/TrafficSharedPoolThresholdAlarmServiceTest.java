package com.pooli.traffic.service.decision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.pooli.traffic.domain.TrafficFamilyMetaSnapshot;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.mapper.TrafficSharedThresholdAlarmLogMapper;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficFamilyMetaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class TrafficSharedPoolThresholdAlarmServiceTest {

    private static final String TEST_TRACE_ID = "trace-threshold-test-001";

    @Mock
    private TrafficFamilyMetaCacheService trafficFamilyMetaCacheService;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficSharedThresholdAlarmLogMapper trafficSharedThresholdAlarmLogMapper;

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @InjectMocks
    private TrafficSharedPoolThresholdAlarmService trafficSharedPoolThresholdAlarmService;

    @BeforeEach
    void setUpMdcTraceId() {
        MDC.put("traceId", TEST_TRACE_ID);
    }

    @AfterEach
    void clearMdcTraceId() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("잔량 퍼센트가 50% 이하이면 50% 임계치 Outbox를 생성한다")
    void createsOutboxWhenFiftyPercentReached() {
        // given
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Seoul"));
        when(trafficFamilyMetaCacheService.getOrLoad(22L)).thenReturn(
                TrafficFamilyMetaSnapshot.builder()
                        .familyId(22L)
                        .poolTotalData(1_000L)
                        .dbRemainingData(400L)
                        .familyThreshold(0L)
                        .thresholdActive(false)
                        .build()
        );

        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(anyLong(), anyString(), anyInt()))
                .thenReturn(0);
        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(eq(22L), anyString(), eq(50)))
                .thenReturn(1);

        // when
        trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(22L);

        // then
        verify(redisOutboxRecordService, times(1)).createPending(
                eq(OutboxEventType.SHARED_POOL_THRESHOLD_REACHED),
                any(),
                eq(TEST_TRACE_ID)
        );
    }

    @Test
    @DisplayName("커스텀 임계치가 기본 임계치와 동일하면 dedupe되어 한 번만 처리된다")
    void dedupesCustomThresholdWhenSameAsDefault() {
        // given
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Seoul"));
        when(trafficFamilyMetaCacheService.getOrLoad(22L)).thenReturn(
                TrafficFamilyMetaSnapshot.builder()
                        .familyId(22L)
                        .poolTotalData(1_000L)
                        .dbRemainingData(250L)
                        .familyThreshold(30L)
                        .thresholdActive(true)
                        .build()
        );

        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(anyLong(), anyString(), anyInt()))
                .thenReturn(0);

        // when
        trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(22L);

        // then
        verify(trafficSharedThresholdAlarmLogMapper, times(1))
                .insertIgnore(eq(22L), anyString(), eq(30));
        verify(redisOutboxRecordService, never()).createPending(any(), any(), any());
    }

    @Test
    @DisplayName("커스텀 임계치가 30이고 도달 시 30 임계치 outbox가 1회 생성된다(값 기준 매핑 전제)")
    void createsSingleThreshold30OutboxWhenCustomIsThirty() {
        // given
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Seoul"));
        when(trafficFamilyMetaCacheService.getOrLoad(22L)).thenReturn(
                TrafficFamilyMetaSnapshot.builder()
                        .familyId(22L)
                        .poolTotalData(1_000L)
                        .dbRemainingData(250L)
                        .familyThreshold(30L)
                        .thresholdActive(true)
                        .build()
        );
        // 나머지 임계치는 이번 케이스 검증 대상이 아니므로 생성되지 않게 고정한다.
        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(eq(22L), anyString(), eq(30))).thenReturn(1);

        // when
        trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(22L);

        // then
        verify(trafficSharedThresholdAlarmLogMapper, times(1))
                .insertIgnore(eq(22L), anyString(), eq(30));
        verify(redisOutboxRecordService, times(1)).createPending(
                eq(OutboxEventType.SHARED_POOL_THRESHOLD_REACHED),
                any(),
                eq(TEST_TRACE_ID)
        );
    }

    @Test
    @DisplayName("custom=0 활성화 + 0퍼센트 도달이면 custom 임계치 outbox를 생성한다")
    void createsCustomZeroThresholdOutboxAtZeroPercent() {
        // given
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Seoul"));
        when(trafficFamilyMetaCacheService.getOrLoad(22L)).thenReturn(
                TrafficFamilyMetaSnapshot.builder()
                        .familyId(22L)
                        .poolTotalData(1_000L)
                        .dbRemainingData(0L)
                        .familyThreshold(0L)
                        .thresholdActive(true)
                        .build()
        );
        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(trafficSharedThresholdAlarmLogMapper.insertIgnore(eq(22L), anyString(), eq(0))).thenReturn(1);

        // when
        trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(22L);

        // then
        verify(trafficSharedThresholdAlarmLogMapper, times(1))
                .insertIgnore(eq(22L), anyString(), eq(0));
        verify(redisOutboxRecordService, times(1)).createPending(
                eq(OutboxEventType.SHARED_POOL_THRESHOLD_REACHED),
                any(),
                eq(TEST_TRACE_ID)
        );
    }
}
