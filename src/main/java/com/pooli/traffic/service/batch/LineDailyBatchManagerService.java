package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;

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

    private final LineDailyBatchJobService lineDailyBatchJobService;

    /**
     * Redis manager lock을 획득한 서버가 target insert 선행 단계를 준비한다.
     * usage sync batch는 target insert 완료 전까지 PENDING 상태로만 생성/조회한다.
     */
    public void run(LocalDate usageDate, String managerInstanceId) {
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
        // 3. target insert batch만 RUNNING으로 열어 이후 target row 생성 단계가 진행될 수 있게 한다.
        boolean targetInsertStarted =
                lineDailyBatchJobService.startPendingBatch(targetInsertBatch, managerInstanceId);

        log.info(
                "line_daily_batch_manager_prepared usageDate={} managerInstanceId={} "
                        + "targetInsertBatchId={} usageSyncBatchId={} targetInsertStarted={}",
                usageDate,
                managerInstanceId,
                targetInsertBatch.getId(),
                usageSyncBatch.getId(),
                targetInsertStarted
        );
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
}
