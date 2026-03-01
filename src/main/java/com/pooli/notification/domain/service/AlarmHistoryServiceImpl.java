package com.pooli.notification.domain.service;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.mapper.AlarmHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlarmHistoryServiceImpl implements AlarmHistoryService {
    private final AlarmHistoryMapper alarmHistoryMapper;

    @Transactional
    public void createAlarm(
            Long userId,
            AlarmCode alarmCode,
            String value
    ) {

        int result = alarmHistoryMapper.insertAlarmHistory(
                userId,
                alarmCode.name(),
                value
        );

        if (result != 1) {
            // MongoDB에 저장?
            System.out.println(userId+"가 "+alarmCode.name()+" : "+value+" 알람미 발송 실패했습니다.");
        }
    }



}
