package com.pooli.notification.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.notification.domain.dto.response.AlarmSettingResDto;
import com.pooli.notification.domain.entity.AlarmSetting;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.exception.NotificationErrorCode;
import com.pooli.notification.mapper.AlarmSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class AlarmSettingServiceImpl implements AlarmSettingService{

    private final AlarmSettingMapper alarmSettingMapper;

    @Transactional
    @Override
    public void updateAlarmSetting(Long userId, AlarmCode code, Boolean enabled) {

        // 1. 존재 여부 확인
        AlarmSetting setting = alarmSettingMapper.findByUserId(userId);

        if (setting == null) {
            // 2. 없으면 기본값 true로 생성
            alarmSettingMapper.insertDefault(userId);
        }

        // 3. 컬럼명 매핑
        String columnName = mapToColumnName(code);

        // 4. 해당 컬럼만 업데이트
        alarmSettingMapper.updateAlarmColumn(userId, columnName, enabled);


    }

    private String mapToColumnName(AlarmCode code) {
        return switch (code) {
            case FAMILY -> "family_alarm";
            case USER -> "user_alarm";
            case POLICY_CHANGE -> "policy_change_alarm";
            case POLICY_LIMIT -> "policy_limit_alarm";
            case PERMISSION -> "permission_alarm";
            case QUESTION -> "question_alarm";
            case OTHERS -> throw new ApplicationException(
                    NotificationErrorCode.INVALID_ALARM_CODE
            );
        };
    }

    @Override
    @Transactional(readOnly = true)
    public AlarmSettingResDto getAlarmSetting(Long userId) {

        AlarmSetting setting = alarmSettingMapper.findByUserId(userId);

        // 1. DB에 없으면 전부 true
        if (setting == null) {
            return AlarmSettingResDto.builder()
                    .familyAlarm(true)
                    .userAlarm(true)
                    .policyChangeAlarm(true)
                    .policyLimitAlarm(true)
                    .permissionAlarm(true)
                    .questionAlarm(true)
                    .build();
        }

        // 2. 있으면 DB 값 반환
        return AlarmSettingResDto.builder()
                .familyAlarm(setting.getFamilyAlarm())
                .userAlarm(setting.getUserAlarm())
                .policyChangeAlarm(setting.getPolicyChangeAlarm())
                .policyLimitAlarm(setting.getPolicyLimitAlarm())
                .permissionAlarm(setting.getPermissionAlarm())
                .questionAlarm(setting.getQuestionAlarm())
                .build();
    }

}

