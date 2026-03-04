package com.pooli.notification.service;

import com.pooli.notification.domain.dto.response.AlarmSettingResDto;
import com.pooli.notification.domain.enums.AlarmCode;

public interface AlarmSettingService {
    void updateAlarmSetting(Long userId, AlarmCode type, Boolean enabled);
    AlarmSettingResDto getAlarmSetting(Long userId);
}
