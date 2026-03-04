package com.pooli.notification.mapper;

import com.pooli.notification.domain.entity.AlarmSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AlarmSettingMapper {

    AlarmSetting findByUserId(Long userId);

    void insertDefault(Long userId);

    int updateAlarmColumn(
            @Param("userId") Long userId,
            @Param("columnName") String columnName,
            @Param("enabled") Boolean enabled
    );

}