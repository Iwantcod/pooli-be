package com.pooli.traffic.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;

/**
 * 트래픽 hydrate와 legacy refill 보상 흐름에서 DB 원천 데이터에 접근하는 MyBatis Mapper입니다.
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
     * 개인풀 원천 잔량을 row lock과 함께 조회합니다.
     */
    Long selectIndividualRemainingForUpdate(@Param("lineId") Long lineId);

    /**
     * legacy refill 보상 흐름에서 개인풀 원천 잔량을 조건부 차감합니다.
     * remaining_data >= deductAmount 조건을 만족할 때만 1건 갱신됩니다.
     */
    int deductIndividualRemaining(
            @Param("lineId") Long lineId,
            @Param("deductAmount") Long deductAmount
    );

    /**
     * legacy refill 보상 흐름에서 개인풀 원천 잔량을 반납합니다.
     */
    int restoreIndividualRemaining(
            @Param("lineId") Long lineId,
            @Param("restoreAmount") Long restoreAmount
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

    /**
     * 공유풀 원천 잔량을 row lock과 함께 조회합니다.
     */
    Long selectSharedRemainingForUpdate(@Param("familyId") Long familyId);

    /**
     * legacy refill 보상 흐름에서 공유풀 원천 잔량을 조건부 차감합니다.
     * pool_remaining_data >= deductAmount 조건을 만족할 때만 1건 갱신됩니다.
     */
    int deductSharedRemaining(
            @Param("familyId") Long familyId,
            @Param("deductAmount") Long deductAmount
    );

    /**
     * legacy refill 보상 흐름에서 공유풀 원천 잔량을 반납합니다.
     */
    int restoreSharedRemaining(
            @Param("familyId") Long familyId,
            @Param("restoreAmount") Long restoreAmount
    );
}
