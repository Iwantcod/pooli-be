package com.pooli.notification.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AlarmHistoryMapper {

    int insertAlarmHistory(
            @Param("lineId") Long lineId,
            @Param("alarmCode") String alarmCode,
            @Param("value") String value
    );
}