package com.pooli.traffic.service.batch;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 일별 사용량 동기화 batch의 manager 역할 진입점이다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyBatchManagerService {

    private static final int TARGET_INSERT_CHUNK_SIZE = 5000;

    private final LineDailyBatchJobService lineDailyBatchJobService;
    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    /**
     * Redis manager lock을 획득한 서버가 target insert 선행 단계를 준비한다.
     * usage sync batch는 target insert 완료 전까지 PENDING 상태로만 생성/조회한다.
     * target insert 완료 또는 완료 이력 확인 후 usage sync가 RUNNING이 되면 worker 시작을 허용한다.
     */
    public boolean run(LocalDate usageDate, String managerInstanceId) {
        // 1. target row 생성을 담당할 batch metadata를 먼저 준비한다.
        LineDailyBatchJob targetInsertBatch = prepareBatchMetadata(
                BatchName.LINE_DAILY_TARGET_INSERT_BATCH,
                usageDate
        );
        // 2. worker 시작 기준이 될 usage sync batch metadata도 같은 usageDate로 준비하되 아직 시작하지 않는다.
        LineDailyBatchJob usageSyncBatch = prepareBatchMetadata(
                BatchName.LINE_DAILY_USAGE_SYNC_BATCH,
                usageDate
        );

        // 3. 이미 완료된 target insert는 재시도 시 target row 생성 단계를 건너뛰고 usage sync 단계로 진행한다.
        boolean targetInsertAlreadyCompleted = targetInsertBatch.getStatus() == LineDailyBatchStatus.COMPLETED;
        boolean targetInsertStarted = false;
        boolean targetInsertResumed = false;
        boolean targetInsertCompleted = targetInsertAlreadyCompleted;
        long targetCount = targetInsertBatch.getTargetCount();
        if (!targetInsertAlreadyCompleted) {
            // 4. PENDING batch는 RUNNING으로 열고, 이미 RUNNING인 batch는 중단된 target insert 재개로 처리한다.
            targetInsertStarted = lineDailyBatchJobService.startPendingBatch(targetInsertBatch, managerInstanceId);
            targetInsertResumed = targetInsertBatch.getStatus() == LineDailyBatchStatus.RUNNING;
            if (!targetInsertStarted && !targetInsertResumed) {
                log.info(
                        "line_daily_batch_manager_target_insert_not_started usageDate={} managerInstanceId={} "
                                + "targetInsertBatchId={} targetInsertStatus={}",
                        usageDate,
                        managerInstanceId,
                        targetInsertBatch.getId(),
                        targetInsertBatch.getStatus()
                );
                return false;
            }

            // 5. 기존 target set의 최대 line_id를 재개 지점으로 삼아 중단 이후 chunk부터 이어간다.
            long lastLineId = lineDailyBatchTargetMapper.selectMaxLineIdByUsageDate(usageDate);
            insertTargetRowsInChunks(usageDate, lastLineId);
            targetCount = lineDailyBatchTargetMapper.countByUsageDate(usageDate);
            // 6. target insert batch는 생성된 target row 수와 같은 success_count로 완료한다.
            targetInsertCompleted =
                    lineDailyBatchJobService.completeRunningTargetInsertBatch(targetInsertBatch, targetCount);
        }

        // 7. target insert가 완료된 뒤에만 usage sync batch를 RUNNING으로 열거나 기존 RUNNING 상태를 인정한다.
        boolean usageSyncAlreadyRunning = usageSyncBatch.getStatus() == LineDailyBatchStatus.RUNNING;
        boolean usageSyncStarted = false;
        if (targetInsertCompleted) {
            if (usageSyncAlreadyRunning) {
                usageSyncStarted = true;
            } else if (usageSyncBatch.getStatus() == LineDailyBatchStatus.PENDING) {
                usageSyncStarted = lineDailyBatchJobService.startPendingUsageSyncBatchWithTargetCount(
                        usageSyncBatch,
                        targetCount,
                        managerInstanceId
                );
            }
        }

        log.info(
                "line_daily_batch_manager_prepared usageDate={} managerInstanceId={} "
                        + "targetInsertBatchId={} usageSyncBatchId={} targetInsertStarted={} "
                        + "targetInsertResumed={} targetInsertAlreadyCompleted={} targetCount={} "
                        + "targetInsertCompleted={} usageSyncAlreadyRunning={} usageSyncStarted={}",
                usageDate,
                managerInstanceId,
                targetInsertBatch.getId(),
                usageSyncBatch.getId(),
                targetInsertStarted,
                targetInsertResumed,
                targetInsertAlreadyCompleted,
                targetCount,
                targetInsertCompleted,
                usageSyncAlreadyRunning,
                usageSyncStarted
        );
        return usageSyncStarted;
    }

    /**
     * 자동 실행용 metadata를 생성하거나 기존 row를 재사용한다.
     */
    private LineDailyBatchJob prepareBatchMetadata(BatchName batchName, LocalDate usageDate) {
        // 1. service 내부의 기존 row 조회/생성 방어 절차를 그대로 사용한다.
        LineDailyBatchJobCreateResult result =
                lineDailyBatchJobService.createPendingForAutomaticRunIfAbsent(batchName, usageDate);
        // 2. caller는 생성 여부보다 이번 실행에서 사용할 metadata row 자체만 필요하다.
        return result.batchJob();
    }

    /**
     * LINE table에 target 생성 전용 인덱스를 추가하지 않고 기존 PK 순서로 chunk scan한다.
     * target insert는 line_id 오름차순 선형 작업이므로, 재개 시 이미 생성된 최대 line_id 이후만 조회한다.
     * 이 전제는 중간 구간보다 큰 line_id를 수동으로 먼저 생성하지 않는 운영 절차에 의존한다.
     */
    private void insertTargetRowsInChunks(LocalDate usageDate, long lastLineId) {
        while (true) {
            List<Long> lineIds = lineDailyBatchTargetMapper.selectActiveLineIdsAfter(
                    lastLineId,
                    TARGET_INSERT_CHUNK_SIZE
            );
            if (lineIds.isEmpty()) {
                return;
            }

            lineDailyBatchTargetMapper.insertIgnoreTargetRows(usageDate, lineIds);
            lastLineId = lineIds.get(lineIds.size() - 1);
        }
    }
}
