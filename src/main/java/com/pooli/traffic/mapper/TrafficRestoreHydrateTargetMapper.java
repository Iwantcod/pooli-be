package com.pooli.traffic.mapper;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTargetType;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;

/**
 * RESTORE_HYDRATE_TARGET target set 생성/claim/상태 전환을 담당한다.
 */
@Mapper
public interface TrafficRestoreHydrateTargetMapper {

    /**
     * DONE 상태가 아닌 target row 수를 조회한다.
     */
    long countNotDoneTargets(@Param("batchName") String batchName);

    /**
     * FAILED 상태 target row 수를 조회한다.
     */
    long countFailedTargets(@Param("batchName") String batchName);

    /**
     * unique key 충돌 시 기존 target row 상태를 보존하며 phase 0 target을 생성한다.
     */
    int insertIgnoreTargets(
            @Param("batchName") String batchName,
            @Param("targetMonthStart") LocalDate targetMonthStart,
            @Param("targetType") TrafficRestoreHydrateTargetType targetType,
            @Param("targetOwnerIds") List<Long> targetOwnerIds
    );

    /**
     * worker가 처리할 phase 0 hydrate target row를 잠금 조회한다.
     */
    List<TrafficRestoreHydrateTarget> selectClaimableTargetsForUpdate(
            @Param("batchName") String batchName,
            @Param("leaseExpiredBefore") LocalDateTime leaseExpiredBefore,
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
     * 현재 worker가 PROCESSING으로 보유한 row를 terminal 상태로 닫는다.
     */
    int markTargetTerminalIfProcessing(
            @Param("id") Long id,
            @Param("status") TrafficRestoreTargetStatus status,
            @Param("workerId") String workerId
    );
}
