package com.pooli.traffic.service.outbox.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolThresholdOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;

@ExtendWith(MockitoExtension.class)
class SharedPoolThresholdOutboxStrategyTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private FamilySharedPoolMapper familySharedPoolMapper;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private SharedPoolThresholdOutboxStrategy sharedPoolThresholdOutboxStrategy;

    @Test
    @DisplayName("thresholdPct=50이면 _50 타입으로 가족 구성원 전체에게 알람을 생성한다")
    void sendsThreshold50AlarmToAllFamilyMembers() {
        // given
        OutboxRetryResult result = executeWithThreshold(50);

        // then
        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(alarmHistoryService, times(1)).createAlarm(11L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_50);
        verify(alarmHistoryService, times(1)).createAlarm(22L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_50);
        verify(alarmHistoryService, times(1)).createAlarm(33L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_50);
    }

    @Test
    @DisplayName("thresholdPct=30이면 _30 타입으로 가족 구성원 전체에게 알람을 생성한다")
    void sendsThreshold30AlarmToAllFamilyMembers() {
        OutboxRetryResult result = executeWithThreshold(30);

        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(alarmHistoryService, times(1)).createAlarm(11L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_30);
        verify(alarmHistoryService, times(1)).createAlarm(22L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_30);
        verify(alarmHistoryService, times(1)).createAlarm(33L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_30);
    }

    @Test
    @DisplayName("thresholdPct=10이면 _10 타입으로 가족 구성원 전체에게 알람을 생성한다")
    void sendsThreshold10AlarmToAllFamilyMembers() {
        OutboxRetryResult result = executeWithThreshold(10);

        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(alarmHistoryService, times(1)).createAlarm(11L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_10);
        verify(alarmHistoryService, times(1)).createAlarm(22L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_10);
        verify(alarmHistoryService, times(1)).createAlarm(33L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_10);
    }

    @Test
    @DisplayName("thresholdPct가 50/30/10 외 값이면 _CUS 타입으로 가족 구성원 전체에게 알람을 생성한다")
    void sendsThresholdCustomAlarmToAllFamilyMembers() {
        OutboxRetryResult result = executeWithThreshold(25);

        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(alarmHistoryService, times(1)).createAlarm(11L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
        verify(alarmHistoryService, times(1)).createAlarm(22L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
        verify(alarmHistoryService, times(1)).createAlarm(33L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
    }

    @Test
    @DisplayName("thresholdPct=0이면 _CUS 타입으로 가족 구성원 전체에게 알람을 생성한다")
    void sendsThresholdZeroAsCustomAlarmToAllFamilyMembers() {
        OutboxRetryResult result = executeWithThreshold(0);

        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(alarmHistoryService, times(1)).createAlarm(11L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
        verify(alarmHistoryService, times(1)).createAlarm(22L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
        verify(alarmHistoryService, times(1)).createAlarm(33L, AlarmCode.FAMILY, AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS);
    }

    private OutboxRetryResult executeWithThreshold(int thresholdPct) {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(100L)
                .payload("{}")
                .build();
        SharedPoolThresholdOutboxPayload payload = SharedPoolThresholdOutboxPayload.builder()
                .familyId(22L)
                .thresholdPct(thresholdPct)
                .targetMonth("2026-03")
                .build();

        when(redisOutboxRecordService.readPayload(record, SharedPoolThresholdOutboxPayload.class)).thenReturn(payload);
        when(familySharedPoolMapper.selectLineIdsByFamilyId(22L)).thenReturn(List.of(11L, 22L, 33L));

        return sharedPoolThresholdOutboxStrategy.execute(record);
    }
}
