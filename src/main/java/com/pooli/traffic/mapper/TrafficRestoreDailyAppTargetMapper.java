package com.pooli.traffic.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;

/**
 * RESTORE_DAILY_APP_TARGET target set 생성/claim/상태 전환을 담당한다.
 */
@Mapper
public interface TrafficRestoreDailyAppTargetMapper {

    /**
     * DONE 상태가 아닌 target row 수를 조회한다.
     */
    long countNotDoneTargets(@Param("batchName") String batchName);

    /**
     * FAILED 상태 target row 수를 조회한다.
     */
    long countFailedTargets(@Param("batchName") String batchName);

    /**
     * worker가 처리할 phase 1 daily app target row를 잠금 조회한다.
     */
    List<TrafficRestoreDailyAppTarget> selectClaimableTargetsForUpdate(
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
