package com.pooli.notification.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AlarmHistoryMapper {

    int insertAlarmHistory(
            @Param("userId") Long userId,
            @Param("alarmCode") String alarmCode,
            @Param("value") String value
    );
}