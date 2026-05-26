package com.pooli.traffic.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;

/**
 * LINE_DAILY_BATCH_JOB metadata 조회/생성을 담당한다.
 * DB unique key를 두지 않으므로 생성 전 기존 row 조회는 서비스 절차에서 반드시 먼저 수행한다.
 */
@Mapper
public interface LineDailyBatchJobMapper {

    /**
     * 자동 실행은 같은 batch_name + usage_date의 최신 row가 있으면 상태와 무관하게 그 row를 사용한다.
     */
    LineDailyBatchJob selectLatestByBatchNameAndUsageDate(
            @Param("batchName") BatchName batchName,
            @Param("usageDate") LocalDate usageDate
    );

    /**
     * worker 시작 감지는 scheduler가 계산한 usage_date의 RUNNING usage sync batch만 대상으로 한다.
     */
    LineDailyBatchJob selectRunningUsageSyncBatchByUsageDate(@Param("usageDate") LocalDate usageDate);

    /**
     * 신규 자동 실행 metadata를 PENDING 상태로 생성한다.
     */
    int insert(LineDailyBatchJob batchJob);

    /**
     * PENDING metadata row만 RUNNING으로 전환한다.
     */
    int updateStatusFromPending(
            @Param("id") Long id,
            @Param("status") LineDailyBatchStatus status,
            @Param("managerInstanceId") String managerInstanceId
    );

    /**
     * target insert batch의 line count를 확정하고 COMPLETED로 종료한다.
     */
    int completeRunningTargetInsertBatch(
            @Param("id") Long id,
            @Param("targetCount") long targetCount
    );

    /**
     * target insert 완료 후 usage sync batch를 RUNNING으로 열고 동일 target_count를 기록한다.
     */
    int startPendingUsageSyncBatchWithTargetCount(
            @Param("id") Long id,
            @Param("targetCount") long targetCount,
            @Param("managerInstanceId") String managerInstanceId
    );

    /**
     * target row terminal 전환 성공 후 usage sync batch의 처리 count를 증가시킨다.
     */
    int incrementUsageSyncProcessedCount(
            @Param("id") Long id,
            @Param("targetStatus") LineDailyBatchTargetStatus targetStatus
    );
}
