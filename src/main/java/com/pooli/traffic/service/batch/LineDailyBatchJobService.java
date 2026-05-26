package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;

import lombok.RequiredArgsConstructor;

/**
 * 일별 배치 metadata 생성 진입점이다.
 * 자동 실행 경로에서는 기존 row 확인 후 없을 때만 PENDING row를 생성한다.
 */
@Service
@RequiredArgsConstructor
public class LineDailyBatchJobService {

    private final LineDailyBatchJobMapper lineDailyBatchJobMapper;

    /**
     * 자동 실행용 batch metadata를 생성하거나 기존 row를 반환한다.
     * 같은 batch_name + usage_date row가 이미 있으면 terminal 상태라도 신규 row를 만들지 않는다.
     */
    @Transactional
    public LineDailyBatchJobCreateResult createPendingForAutomaticRunIfAbsent(
            BatchName batchName,
            LocalDate usageDate
    ) {
        // 1. 동일 날짜의 기존 metadata가 있으면 완료/실패 상태라도 자동 신규 실행을 만들지 않는다.
        LineDailyBatchJob existing =
                lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(batchName, usageDate);
        if (existing != null) {
            return new LineDailyBatchJobCreateResult(false, existing);
        }

        // 2. 기존 metadata가 없을 때만 line 단위 count를 0으로 초기화한 PENDING row를 생성한다.
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .batchName(batchName)
                .usageDate(usageDate)
                .status(LineDailyBatchStatus.PENDING)
                .targetCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .skippedCount(0L)
                .build();
        lineDailyBatchJobMapper.insert(batchJob);

        return new LineDailyBatchJobCreateResult(true, batchJob);
    }

    /**
     * worker 시작 감지용 조회이다.
     * target insert가 usage sync batch를 RUNNING으로 열기 전에는 worker가 target claim을 시작하지 않는다.
     */
    @Transactional(readOnly = true)
    public LineDailyBatchJob findRunningUsageSyncBatch(LocalDate usageDate) {
        return lineDailyBatchJobMapper.selectRunningUsageSyncBatchByUsageDate(usageDate);
    }

    /**
     * 이미 준비된 PENDING metadata row를 실제 실행 상태로 전환한다.
     * target insert batch를 여는 용도로 사용하며, PENDING이 아닌 row는 건드리지 않는다.
     */
    @Transactional
    public boolean startPendingBatch(LineDailyBatchJob batchJob, String managerInstanceId) {
        // 1. 호출 시점에 이미 실행/종료된 metadata라면 상태 전환 SQL을 실행하지 않는다.
        if (batchJob.getStatus() != LineDailyBatchStatus.PENDING) {
            return false;
        }

        // 2. DB에서도 PENDING 조건을 다시 확인해 동시 manager 실행 시 중복 RUNNING 전환을 막는다.
        int updated = lineDailyBatchJobMapper.updateStatusFromPending(
                batchJob.getId(),
                LineDailyBatchStatus.RUNNING,
                managerInstanceId
        );
        // 3. affected rows 1건만 manager가 이번 실행을 열었다는 신호로 반환한다.
        return updated == 1;
    }

    /**
     * target insert batch의 target_count와 success_count를 실제 target row 수로 맞춘 뒤 종료한다.
     */
    @Transactional
    public boolean completeRunningTargetInsertBatch(LineDailyBatchJob batchJob, long targetCount) {
        int updated = lineDailyBatchJobMapper.completeRunningTargetInsertBatch(
                batchJob.getId(),
                targetCount
        );
        return updated == 1;
    }

    /**
     * usage sync batch를 RUNNING으로 열며 worker가 처리할 target_count를 함께 확정한다.
     */
    @Transactional
    public boolean startPendingUsageSyncBatchWithTargetCount(
            LineDailyBatchJob batchJob,
            long targetCount,
            String managerInstanceId
    ) {
        if (batchJob.getStatus() != LineDailyBatchStatus.PENDING) {
            return false;
        }

        int updated = lineDailyBatchJobMapper.startPendingUsageSyncBatchWithTargetCount(
                batchJob.getId(),
                targetCount,
                managerInstanceId
        );
        return updated == 1;
    }
}
