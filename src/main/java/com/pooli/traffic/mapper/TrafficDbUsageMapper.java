package com.pooli.traffic.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * DB fallback 차감 시 집계 사용량 조회/증분 반영을 담당하는 Mapper입니다.
 */
@Mapper
public interface TrafficDbUsageMapper {

    /**
     * line의 일별 총 사용량을 조회합니다.
     */
    Long selectDailyTotalUsage(
            @Param("lineId") Long lineId,
            @Param("usageDate") LocalDate usageDate
    );

    /**
     * line+app의 일별 사용량을 조회합니다.
     */
    Long selectDailyAppUsage(
            @Param("lineId") Long lineId,
            @Param("appId") Integer appId,
            @Param("usageDate") LocalDate usageDate
    );

    /**
     * line 기준 월 공유풀 사용량을 조회합니다.
     */
    Long selectMonthlySharedUsageByLine(
            @Param("lineId") Long lineId,
            @Param("monthStart") LocalDate monthStart,
            @Param("nextMonthStart") LocalDate nextMonthStart
    );

    /**
     * line의 일별 총 사용량을 upsert+증분합니다.
     */
    int upsertDailyTotalUsage(
            @Param("lineId") Long lineId,
            @Param("usageDate") LocalDate usageDate,
            @Param("usedBytes") long usedBytes
    );

    /**
     * line+app의 일별 사용량을 upsert+증분합니다.
     */
    int upsertDailyAppUsage(
            @Param("lineId") Long lineId,
            @Param("appId") Integer appId,
            @Param("usageDate") LocalDate usageDate,
            @Param("usedBytes") long usedBytes
    );

    /**
     * shared 차감 시 가족 공유 일별 사용량을 upsert+증분합니다.
     */
    int upsertFamilySharedUsageDaily(
            @Param("familyId") Long familyId,
            @Param("lineId") Long lineId,
            @Param("usageDate") LocalDate usageDate,
            @Param("usedBytes") long usedBytes
    );
}
