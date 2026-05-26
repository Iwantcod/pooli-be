package com.pooli.traffic.service.batch;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;

import lombok.extern.slf4j.Slf4j;

/**
 * Usage sync worker loop 진입점이다.
 * target claim과 Redis/MySQL 동기화는 이후 worker 처리 마일스톤에서 이 메서드에 연결한다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
public class LineDailyUsageSyncWorkerService {

    public void run(LineDailyBatchJob batchJob) {
        log.info(
                "line_daily_usage_sync_worker_started batchJobId={} usageDate={}",
                batchJob.getId(),
                batchJob.getUsageDate()
        );
    }
}
