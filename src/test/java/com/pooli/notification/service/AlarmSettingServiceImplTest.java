package com.pooli.notification.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.notification.domain.dto.response.AlarmSettingResDto;
import com.pooli.notification.domain.entity.AlarmSetting;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.exception.NotificationErrorCode;
import com.pooli.notification.mapper.AlarmSettingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlarmSettingServiceImplTest {

    @Mock
    private AlarmSettingMapper alarmSettingMapper;

    @InjectMocks
    private AlarmSettingServiceImpl alarmSettingService;

    @Nested
    @DisplayName("updateAlarmSetting()")
    class UpdateAlarmSetting {

        @Test
        @DisplayName("설정이 없으면 기본값 생성 후 업데이트")
        void updateAlarmSetting_createDefaultAndUpdate() {
            // given
            Long userId = 1L;
            Boolean enabled = false;

            when(alarmSettingMapper.findByUserId(userId)).thenReturn(null);

            // when
            alarmSettingService.updateAlarmSetting(userId, AlarmCode.FAMILY, enabled);

            // then
            InOrder inOrder = inOrder(alarmSettingMapper);
            inOrder.verify(alarmSettingMapper).findByUserId(userId);
            inOrder.verify(alarmSettingMapper).insertDefault(userId);
            inOrder.verify(alarmSettingMapper).updateAlarmColumn(userId, "family_alarm", enabled);
        }

        @Test
        @DisplayName("설정이 존재하면 기본값 생성 없이 업데이트")
        void updateAlarmSetting_updateExisting() {
            // given
            Long userId = 2L;
            Boolean enabled = true;
            AlarmSetting existingSetting = AlarmSetting.builder().build();
            when(alarmSettingMapper.findByUserId(userId)).thenReturn(existingSetting);

            // when
            alarmSettingService.updateAlarmSetting(userId, AlarmCode.USER, enabled);

            // then
            verify(alarmSettingMapper, never()).insertDefault(any());
            verify(alarmSettingMapper).updateAlarmColumn(userId, "user_alarm", enabled);
        }

        @Test
        @DisplayName("OTHERS 알람 코드 선택 시 예외 발생")
        void updateAlarmSetting_invalidCode() {
            // given
            Long userId = 3L;
            Boolean enabled = true;

            // when & then
            ApplicationException exception = assertThrows(ApplicationException.class, () ->
                    alarmSettingService.updateAlarmSetting(userId, AlarmCode.OTHERS, enabled)
            );

            assertThat(exception.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_ALARM_CODE);
        }
    }

    @Nested
    @DisplayName("getAlarmSetting()")
    class GetAlarmSetting {

        @Test
        @DisplayName("DB에 없으면 모든 알람 true 반환")
        void getAlarmSetting_defaultTrue() {
            // given
            Long userId = 1L;
            when(alarmSettingMapper.findByUserId(userId)).thenReturn(null);

            // when
            AlarmSettingResDto result = alarmSettingService.getAlarmSetting(userId);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getFamilyAlarm()).isTrue();
            assertThat(result.getUserAlarm()).isTrue();
            assertThat(result.getPolicyChangeAlarm()).isTrue();
            assertThat(result.getPolicyLimitAlarm()).isTrue();
            assertThat(result.getPermissionAlarm()).isTrue();
            assertThat(result.getQuestionAlarm()).isTrue();
        }

        @Test
        @DisplayName("DB에 있으면 DB 값 반환")
        void getAlarmSetting_existing() {
            // given
            Long userId = 2L;
            AlarmSetting setting = AlarmSetting.builder()
                    .familyAlarm(false)
                    .userAlarm(true)
                    .policyChangeAlarm(false)
                    .policyLimitAlarm(true)
                    .permissionAlarm(false)
                    .questionAlarm(true)
                    .build();

            when(alarmSettingMapper.findByUserId(userId)).thenReturn(setting);

            // when
            AlarmSettingResDto result = alarmSettingService.getAlarmSetting(userId);

            // then
            assertThat(result.getFamilyAlarm()).isFalse();
            assertThat(result.getUserAlarm()).isTrue();
            assertThat(result.getPolicyChangeAlarm()).isFalse();
            assertThat(result.getPolicyLimitAlarm()).isTrue();
            assertThat(result.getPermissionAlarm()).isFalse();
            assertThat(result.getQuestionAlarm()).isTrue();
        }
    }
}