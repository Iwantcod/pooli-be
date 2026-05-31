package com.pooli.traffic.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * TRAFFIC_DEDUCT_DONE 테이블 접근을 담당하는 MyBatis Mapper입니다.
 */
@Mapper
public interface TrafficDeductDoneLogMapper {

    /**
     * traceId 기준 완료 로그 존재 여부를 조회합니다.
     */
    boolean existsByTraceId(@Param("traceId") String traceId);

    /**
     * 완료 로그를 신규로 삽입합니다.
     */
    int insert(TrafficDeductDoneLog doneLog);

    /**
     * phase 2 replay 대상 done log를 잠금 조회한다.
     */
    List<TrafficDeductDoneLog> selectClaimableRestoreLogsForUpdate(
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("leaseExpiredBefore") LocalDateTime leaseExpiredBefore,
            @Param("limit") int limit
    );

    /**
     * 잠금 조회한 done log를 PROCESSING으로 전환한다.
     */
    int markRestoreLogsProcessing(
            @Param("ids") List<Long> ids,
            @Param("workerId") String workerId
    );

    /**
     * PROCESSING done log를 DONE terminal 상태로 전환한다.
     */
    int markRestoreDoneIfProcessing(
            @Param("id") Long id,
            @Param("workerId") String workerId
    );

    /**
     * PROCESSING done log를 FAILED terminal 상태로 전환한다.
     */
    int markRestoreFailedIfProcessing(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("errorMessage") String errorMessage
    );

    /**
     * 운영 재개 시 anchor date 이전 FAILED done log만 RETRYABLE로 되돌린다.
     */
    int resetFailedRestoreLogsToRetryable(@Param("endExclusive") LocalDateTime endExclusive);
}
