package com.pooli.notification.service;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;

import java.util.List;
import java.util.Map;

public interface AlarmHistoryService {
    void createAlarm(Long userId, AlarmCode alarmCode, AlarmType alarmType, Map<String, Object> values);
    void createNotificationAlarms(List<Long> lineIds, Map<String, Object> values);
}
