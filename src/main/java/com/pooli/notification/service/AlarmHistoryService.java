package com.pooli.notification.service;

import com.pooli.notification.domain.enums.AlarmCode;

public interface AlarmHistoryService {
    void createAlarm(Long userId, AlarmCode alarmCode, String value);

}
