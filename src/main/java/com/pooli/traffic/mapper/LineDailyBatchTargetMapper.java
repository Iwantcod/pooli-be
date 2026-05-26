package com.pooli.traffic.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;

/**
 * LINE_DAILY_BATCH_TARGET target set 생성/조회를 담당한다.
 * worker claim과 terminal 상태 전환은 현재 worker가 보유한 PROCESSING row 기준으로 수행한다.
 */
@Mapper
public interface LineDailyBatchTargetMapper {

    /**
     * usage_date + line_id unique key 기준으로 단일 target row를 조회한다.
     */
    LineDailyBatchTarget selectByUsageDateAndLineId(
            @Param("usageDate") LocalDate usageDate,
            @Param("lineId") Long lineId
    );

    /**
     * batch metadata id 없이 usage_date 기준 target set 크기를 조회한다.
     */
    long countByUsageDate(@Param("usageDate") LocalDate usageDate);

    /**
     * worker가 더 선점할 row가 없을 때 남은 non-terminal row가 있는지 확인한다.
     */
    long countNonTerminalByUsageDate(@Param("usageDate") LocalDate usageDate);

    /**
     * target insert batch 재개 시 이미 확보된 가장 큰 line_id를 조회한다.
     */
    long selectMaxLineIdByUsageDate(@Param("usageDate") LocalDate usageDate);

    /**
     * LINE PK 순서로 target row 생성 대상 line_id chunk를 조회한다.
     */
    List<Long> selectActiveLineIdsAfter(
            @Param("lastLineId") Long lastLineId,
            @Param("limit") int limit
    );

    /**
     * usage_date + line_id unique key 충돌 시 기존 target row 상태를 보존한다.
     */
    int insertIgnoreTargetRows(
            @Param("usageDate") LocalDate usageDate,
            @Param("lineIds") List<Long> lineIds
    );

    /**
     * worker가 처리할 target row를 짧은 트랜잭션 안에서 잠금 조회한다.
     */
    List<LineDailyBatchTarget> selectClaimableTargetsForUpdate(
            @Param("usageDate") LocalDate usageDate,
            @Param("processingLeaseTimeoutSeconds") int processingLeaseTimeoutSeconds,
            @Param("limit") int limit
    );

    /**
     * 잠금 조회한 target row를 PROCESSING으로 선점한다.
     */
    int markTargetsProcessing(
            @Param("ids") List<Long> ids,
            @Param("workerId") String workerId
    );

    /**
     * 현재 worker가 PROCESSING으로 선점한 row만 terminal 상태로 닫는다.
     */
    int markTargetTerminalIfProcessing(
            @Param("id") Long id,
            @Param("status") LineDailyBatchTargetStatus status,
            @Param("workerId") String workerId
    );

    /**
     * 현재 worker가 PROCESSING으로 보유한 row를 재시도 가능 상태로 되돌리고 retry_count를 증가시킨다.
     */
    int markTargetRetryableIfProcessing(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("lastErrorCode") String lastErrorCode,
            @Param("lastErrorMessage") String lastErrorMessage
    );

    /**
     * 현재 worker가 PROCESSING으로 보유한 row를 FAILED terminal 상태로 닫는다.
     */
    int markTargetFailedIfProcessing(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("lastErrorCode") String lastErrorCode,
            @Param("lastErrorMessage") String lastErrorMessage
    );
}
