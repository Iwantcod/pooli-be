package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 트래픽 hydrate와 legacy refill 보상 흐름에서 DB 원천 데이터에 접근하는 MyBatis Mapper입니다.
 */
@Mapper
public interface TrafficRefillSourceMapper {

    /**
     * 개인풀 hydrate에 사용할 원천 데이터량을 조회합니다.
     */
    Long selectIndividualRemaining(@Param("lineId") Long lineId);

    /**
     * 개인 회선의 요금제 QoS 속도 제한 값을 조회합니다.
     */
    Long selectIndividualQosSpeedLimit(@Param("lineId") Long lineId);

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
     * 공유풀 hydrate에 사용할 원천 데이터량을 조회합니다.
     */
    Long selectSharedRemaining(@Param("familyId") Long familyId);

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
