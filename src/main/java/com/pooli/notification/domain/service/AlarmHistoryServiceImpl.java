package com.pooli.notification.domain.service;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.mapper.AlarmHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AlarmHistoryServiceImpl implements AlarmHistoryService {
    private final AlarmHistoryMapper alarmHistoryMapper;
    private final ObjectMapper objectMapper;

    // 사용 시 type이란 키가 필수
    @Transactional
    public void createAlarm(
            Long userId,
            AlarmCode alarmCode,
            Map<String, Object> values
    ) {

        // 1. type 존재 여부 체크
        if (values == null || !values.containsKey("type")) {

            System.out.println("알림 생성 실패 - type 키 없음 : userId=" + userId);

            // 필요하면 MongoDB fallback 저장 위치
            // mongoAlarmFailRepository.save(...);

            return; // 저장 안 함
        }
        try {
            String jsonValue = objectMapper.writeValueAsString(values);

            int result = alarmHistoryMapper.insertAlarmHistory(
                    userId,
                    alarmCode.name(),
                    jsonValue
            );

            if (result != 1) {
                System.out.println(userId + "가 " + alarmCode.name() + " : 알람 저장 실패");
                // MongoDB 기록
            }

        } catch (JsonProcessingException e) {
            System.out.println("알림 JSON 변환 실패 : " + e.getMessage());
        }
    }


}
