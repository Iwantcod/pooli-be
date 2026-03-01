package com.pooli.notification.domain.service;

import com.pooli.notification.domain.enums.AlarmCode;

import java.util.Map;

public interface AlarmHistoryService {
    void createAlarm(Long userId, AlarmCode alarmCode, Map<String, Object> values);

}
