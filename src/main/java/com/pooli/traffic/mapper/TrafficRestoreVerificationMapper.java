package com.pooli.traffic.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.restore.TrafficRestoreVerificationLineRange;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationTarget;

/**
 * Redis 복구 전체 검증에 사용할 RDB 기준값을 산출한다.
 */
@Mapper
public interface TrafficRestoreVerificationMapper {

    /**
     * 복구 대상 범위에서 검증해야 하는 line_id 최소/최대 범위를 조회한다.
     */
    TrafficRestoreVerificationLineRange selectVerificationLineRange(
            @Param("startInclusive") LocalDate startInclusive,
            @Param("endExclusive") LocalDate endExclusive,
            @Param("startDateTimeInclusive") LocalDateTime startDateTimeInclusive,
            @Param("endDateTimeExclusive") LocalDateTime endDateTimeExclusive
    );

    /**
     * 복구 대상 하루와 line range에 대한 사용량 key 기준값을 조회한다.
     */
    List<TrafficRestoreVerificationTarget> selectUsageVerificationTargets(
            @Param("usageDate") LocalDate usageDate,
            @Param("dayStartInclusive") LocalDateTime dayStartInclusive,
            @Param("dayEndExclusive") LocalDateTime dayEndExclusive,
            @Param("lineIdStartInclusive") Long lineIdStartInclusive,
            @Param("lineIdEndInclusive") Long lineIdEndInclusive
    );

    /**
     * 복구 전체 범위와 line range에 대한 월별 잔량 key 기준값을 조회한다.
     */
    List<TrafficRestoreVerificationTarget> selectRemainingVerificationTargets(
            @Param("startInclusive") LocalDate startInclusive,
            @Param("endExclusive") LocalDate endExclusive,
            @Param("startDateTimeInclusive") LocalDateTime startDateTimeInclusive,
            @Param("endDateTimeExclusive") LocalDateTime endDateTimeExclusive,
            @Param("lineIdStartInclusive") Long lineIdStartInclusive,
            @Param("lineIdEndInclusive") Long lineIdEndInclusive
    );

    /**
     * 전역 정책 key 기준값을 조회한다.
     */
    List<TrafficRestoreVerificationTarget> selectPolicyVerificationTargets();
}
