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
     * metric collector는 최신 usage sync batch 한 건을 기준으로 gauge 값을 갱신한다.
     */
    LineDailyBatchJob selectLatestByBatchName(@Param("batchName") BatchName batchName);

    /**
     * worker 시작 감지는 scheduler가 계산한 usage_date의 RUNNING usage sync batch만 대상으로 한다.
     */
    LineDailyBatchJob selectRunningUsageSyncBatchByUsageDate(@Param("usageDate") LocalDate usageDate);

    /**
     * 신규 자동 실행 metadata를 PENDING 상태로 생성한다.
     */
    int insert(LineDailyBatchJob batchJob);

    /**
     * 운영 rerun은 자동 생성 방어 경로와 분리해 새 RUNNING usage sync metadata row를 생성한다.
     */
    int insertRunningRerunUsageSyncBatch(LineDailyBatchJob batchJob);

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

    /**
     * usage sync batch의 DONE/SKIPPED 처리 count를 bulk terminal 전환 결과만큼 증가시킨다.
     */
    int incrementUsageSyncSuccessAndSkippedCount(
            @Param("batchJobId") Long batchJobId,
            @Param("successDelta") int successDelta,
            @Param("skippedDelta") int skippedDelta
    );

    /**
     * 모든 target row가 terminal count에 반영된 경우에만 usage sync batch를 완료한다.
     */
    int completeRunningUsageSyncBatchIfCountsMatch(@Param("id") Long id);

    /**
     * restore phase target이 모두 DONE일 때 RUNNING metadata row를 COMPLETED로 닫는다.
     */
    int completeRunningRestorePhaseBatch(
            @Param("id") Long id,
            @Param("batchName") BatchName batchName
    );

    /**
     * restore phase RUNNING metadata row를 FAILED로 전환한다.
     */
    int failRunningRestorePhaseBatch(
            @Param("id") Long id,
            @Param("batchName") BatchName batchName,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    /**
     * FAILED 또는 ABANDONED restore phase metadata row를 RUNNING으로 재개한다.
     */
    int restartRestorePhaseBatch(
            @Param("id") Long id,
            @Param("batchName") BatchName batchName,
            @Param("managerInstanceId") String managerInstanceId
    );
}
