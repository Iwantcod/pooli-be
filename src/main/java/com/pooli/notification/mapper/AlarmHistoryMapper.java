package com.pooli.notification.mapper;

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
}