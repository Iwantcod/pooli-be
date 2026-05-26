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
 * target claim 이후 Redis 조회와 MySQL 반영 트랜잭션을 target row 단위로 연결한다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncWorkerService {

    static final int WORKER_CLAIM_CHUNK_SIZE = 100;

    private final LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;
    private final LineDailyUsageRedisReader lineDailyUsageRedisReader;
    private final LineDailyUsageSyncPersistenceService lineDailyUsageSyncPersistenceService;

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

        for (LineDailyBatchTarget target : claimedTargets) {
            LineDailyUsageReadResult snapshot = lineDailyUsageRedisReader.read(target);
            log.info(
                    "line_daily_usage_sync_worker_read_usage targetId={} usageDate={} lineId={} hasAnyUsage={}",
                    target.getId(),
                    target.getUsageDate(),
                    target.getLineId(),
                    snapshot.hasAnyUsage()
            );
            lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                    batchJob.getId(),
                    target,
                    snapshot,
                    workerId
            );
        }
    }

    private String buildWorkerId(LocalDate usageDate) {
        return "line-daily-worker:" + usageDate + ":" + UUID.randomUUID();
    }
}
