package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 공유풀 임계치 알람 월 1회 dedupe 로그 저장소 Mapper입니다.
 */
@Mapper
public interface TrafficSharedThresholdAlarmLogMapper {

    /**
     * (familyId, targetMonth, thresholdPct) 조합으로 1회만 insert합니다.
     *
     * @return 1이면 신규 insert, 0이면 이미 존재
     */
    int insertIgnore(
            @Param("familyId") Long familyId,
            @Param("targetMonth") String targetMonth,
            @Param("thresholdPct") Integer thresholdPct
    );
}
