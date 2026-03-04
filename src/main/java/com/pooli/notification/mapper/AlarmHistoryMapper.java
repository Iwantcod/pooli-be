package com.pooli.notification.mapper;

import com.pooli.notification.domain.dto.response.NotiSendResDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AlarmHistoryMapper {

    int insertAlarmHistory(
            @Param("lineId") Long lineId,
            @Param("alarmCode") String alarmCode,
            @Param("value") String value
    );

    int insertNotificationAlarms(
            @Param("lineIds") List<Long> lineIds,
            @Param("alarmCode") String alarmCode,
            @Param("value") String value
    );

    List<NotiSendResDto> findAlarmHistoryPage(
            @Param("lineId") Long lineId,
            @Param("isRead") Boolean isRead,
            @Param("code") String code,
            @Param("offset") int offset,
            @Param("size") int size
    );

    Long countAlarmHistory(
            @Param("lineId") Long lineId,
            @Param("isRead") Boolean isRead,
            @Param("code") String code
    );

    Long countUnreadByLineId(@Param("lineId") Long lineId);
}