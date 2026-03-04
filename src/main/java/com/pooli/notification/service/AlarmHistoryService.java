package com.pooli.notification.service;

import com.pooli.common.dto.PagingResDto;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.dto.response.NotiSendResDto;
import com.pooli.notification.domain.dto.response.UnreadCountsResDto;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;

import java.util.List;
import java.util.Map;

public interface AlarmHistoryService {
    void createAlarm(Long userId, AlarmCode alarmCode, AlarmType alarmType);
    void createAlarm(Long userId, AlarmCode alarmCode, AlarmType alarmType, Map<String, String> values);
    void sendNotification(NotiSendReqDto request);

    PagingResDto<NotiSendResDto> getNotifications(
            Long userId,
            Long lineId,
            Integer page,
            Integer size,
            Boolean isRead,
            AlarmCode code
    );

    UnreadCountsResDto getUnreadCounts(Long userId, Long lineId);

    NotiSendResDto readOne(Long alarmHistoryId, Long lineId);

    UnreadCountsResDto readAll(Long lineId);

}
