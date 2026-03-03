package com.pooli.notification.service;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.mapper.AlarmHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AlarmHistoryServiceImpl implements AlarmHistoryService {
    private final AlarmHistoryMapper alarmHistoryMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public void createAlarm(
            Long lineId,
            AlarmCode alarmCode,
            AlarmType alarmType,
            Map<String, Object> values
    ) {

        // 1. values가 있는지 확인하고 type을 Map에 추가
        if (values == null) {
            values = new HashMap<>();
        }
        // AlarmType의 이름을 문자열로 넣어 나중에 JSON 파싱이 쉽도록 합니다.
        values.put("type", alarmType.name());

        try {
            String jsonValue = objectMapper.writeValueAsString(values);

            int result = alarmHistoryMapper.insertAlarmHistory(
                    lineId,
                    alarmCode.name(),
                    jsonValue
            );

            if (result != 1) {
                System.out.println("회선 Id :" + lineId + "의 " + alarmCode.name() + " : 알람 저장 실패");
                // MongoDB 기록
            }

        } catch (JsonProcessingException e) {
            System.out.println("알림 JSON 변환 실패 : " + e.getMessage());
        }
    }

    @Transactional
    @Override
    public void createNotificationAlarms(
            List<Long> lineIds, Map<String, Object> values
    ) {

        if (lineIds == null || lineIds.isEmpty()) {
            return;
        }

        if (values == null) {
            values = new HashMap<>();
        }

        // 🔥 type은 무조건 NOTIFICATION
        values.put("type", "NOTIFICATION");

        try {
            String jsonValue = objectMapper.writeValueAsString(values);

            int result = alarmHistoryMapper.insertNotificationAlarms(
                    lineIds,
                    alarmCode.name(),
                    jsonValue
            );

            if (result != lineIds.size()) {
                System.out.println("일부 알림 저장 실패");
            }

        } catch (JsonProcessingException e) {
            System.out.println("알림 JSON 변환 실패 : " + e.getMessage());
        }
    }


}
