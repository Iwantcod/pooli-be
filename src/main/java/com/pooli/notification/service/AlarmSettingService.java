package com.pooli.notification.service;

import com.pooli.notification.domain.dto.response.AlarmSettingResDto;
import com.pooli.notification.domain.enums.AlarmType;

public interface AlarmSettingService {
    void updateAlarmSetting(Long userId, AlarmType type, Boolean enabled);
    AlarmSettingResDto getAlarmSetting(Long userId);
}
