package com.pooli.traffic.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;

/**
 * Redis 월별 잔량 snapshot hydrate에 필요한 RDB source를 조회/갱신하는 MyBatis Mapper입니다.
 *
 * <p>이 Mapper는 실시간 잔량 fallback이나 차감 처리를 담당하지 않습니다. Redis `remaining_*_amount`
 * hash가 비어 있을 때 snapshot을 만들 수 있도록 LINE/FAMILY의 월 기준 source 값과 refresh 기준 시각만
 * 제공하고, 실제 잔량 판단과 차감은 Redis snapshot을 사용하는 상위 서비스가 담당합니다.
 */
@Mapper
public interface TrafficBalanceSnapshotSourceMapper {

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
