package com.pooli.notification.domain.service;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;

import java.util.Map;

public interface AlarmHistoryService {
    void createAlarm(Long userId, AlarmCode alarmCode, AlarmType alarmType, Map<String, Object> values);

}
