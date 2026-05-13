package com.pooli.traffic.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;

/**
 * 트래픽 hydrate 흐름에서 DB 원천 데이터에 접근하는 MyBatis Mapper입니다.
 */
@Mapper
public interface TrafficRefillSourceMapper {

    /**
     * 개인풀 hydrate에 사용할 잔량/QoS 스냅샷을 조회합니다.
     */
    TrafficIndividualBalanceSnapshot selectIndividualBalanceSnapshot(@Param("lineId") Long lineId);

    /**
     * target 월보다 오래된 개인풀 월 잔량이면 월초 기본량으로 조건부 갱신합니다.
     */
    int refreshIndividualBalanceIfBeforeTargetMonth(
            @Param("lineId") Long lineId,
            @Param("targetMonthStart") LocalDateTime targetMonthStart
    );

    /**
     * 공유풀 hydrate에 사용할 잔량 스냅샷을 조회합니다.
     */
    TrafficSharedBalanceSnapshot selectSharedBalanceSnapshot(@Param("familyId") Long familyId);

    /**
     * target 월보다 오래된 공유풀 월 잔량이면 월초 기본량으로 조건부 갱신합니다.
     */
    int refreshSharedBalanceIfBeforeTargetMonth(
            @Param("familyId") Long familyId,
            @Param("targetMonthStart") LocalDateTime targetMonthStart
    );

}
