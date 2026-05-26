package com.pooli.traffic.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrafficDailyUsageBatchMapper {

    /**
     * 일별 동기화 배치는 target row 단위로 한 번만 성공해야 하므로 중복 key를 갱신하지 않는다.
     * 동일 usageDate/lineId가 이미 있으면 DB unique key 예외를 통해 worker 실패 처리로 넘긴다.
     */
    int insertDailyTotalUsage(
            @Param("usageDate") LocalDate usageDate,
            @Param("lineId") Long lineId,
            @Param("totalUsageData") Long totalUsageData
    );

    /**
     * 앱별 일일 사용량을 개인풀, 공유풀, QoS 컬럼으로 최초 삽입한다.
     * 중복 key 발생 시 기존 사용량을 덮어쓰지 않고 배치 정합성 오류로 다룬다.
     */
    int insertDailyAppUsages(
            @Param("usageDate") LocalDate usageDate,
            @Param("lineId") Long lineId,
            @Param("appUsages") List<DailyAppUsageBatchInsertRow> appUsages
    );

    /**
     * 공유풀 일일 사용량만 삽입하며 contribution_amount는 배치 책임이 아니므로 0으로만 생성한다.
     * 기존 row가 있으면 기여량을 보존하기 위해 update 없이 실패시킨다.
     */
    int insertFamilySharedDailyUsage(
            @Param("usageDate") LocalDate usageDate,
            @Param("familyId") Long familyId,
            @Param("lineId") Long lineId,
            @Param("usageAmount") Long usageAmount
    );
}
