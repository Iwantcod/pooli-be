package com.pooli.traffic.service.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Usage sync worker loop 진입점이다.
 * MySQL 반영과 target terminal 전환은 이후 worker 처리 마일스톤에서 PROCESSING row 기준으로 연결한다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncWorkerService {

    static final int WORKER_CLAIM_CHUNK_SIZE = 100;

    private final LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;
    private final LineDailyUsageRedisReader lineDailyUsageRedisReader;

    public void run(LineDailyBatchJob batchJob) {
        String workerId = buildWorkerId(batchJob.getUsageDate());
        List<LineDailyBatchTarget> claimedTargets = lineDailyBatchTargetClaimService.claim(
                batchJob.getUsageDate(),
                workerId,
                WORKER_CLAIM_CHUNK_SIZE
        );

        log.info(
                "line_daily_usage_sync_worker_claimed batchJobId={} usageDate={} workerId={} claimedCount={}",
                batchJob.getId(),
                batchJob.getUsageDate(),
                workerId,
                claimedTargets.size()
        );

        /*
         * 처리 순서:
         * 1. target row의 usage_date + line_id로 일별 총 사용량, 앱별 사용량, 공유풀 일별 사용량 key를 조회한다.
         * 2. 조회 결과에 사용량이 하나도 없으면 후속 단계에서 SKIPPED 전환 대상이 된다.
         * 3. 일부라도 사용량이 있으면 후속 단계에서 존재하는 사용량만 DB insert하고 DONE 전환 대상이 된다.
         *
         * DB insert, DONE/SKIPPED terminal 전환, metadata count 증가는 같은 MySQL 트랜잭션으로 묶어야 하므로
         * 여기서는 수행하지 않고 다음 worker 마일스톤에서 연결한다.
         */
        for (LineDailyBatchTarget target : claimedTargets) {
            LineDailyUsageReadResult snapshot = lineDailyUsageRedisReader.read(target);
            log.info(
                    "line_daily_usage_sync_worker_read_usage targetId={} usageDate={} lineId={} hasAnyUsage={}",
                    target.getId(),
                    target.getUsageDate(),
                    target.getLineId(),
                    snapshot.hasAnyUsage()
            );
        }
    }

    private String buildWorkerId(LocalDate usageDate) {
        return "line-daily-worker:" + usageDate + ":" + UUID.randomUUID();
    }
}
