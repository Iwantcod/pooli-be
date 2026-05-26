package com.pooli.traffic.service.batch;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.mapper.DailyAppUsageBatchInsertRow;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;
import com.pooli.traffic.mapper.TrafficDailyUsageBatchMapper;

import lombok.RequiredArgsConstructor;

/**
 * worker가 Redis에서 읽은 사용량을 MySQL에 반영하고 target row를 terminal 상태로 닫는다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncPersistenceService {

    private final TrafficDailyUsageBatchMapper trafficDailyUsageBatchMapper;
    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;
    private final LineDailyBatchJobMapper lineDailyBatchJobMapper;

    /**
     * Redis snapshot 내용에 따라 target row를 DONE 또는 SKIPPED로 닫는다.
     */
    @Transactional
    public void persistUsageAndCompleteTarget(
            Long batchJobId,
            LineDailyBatchTarget target,
            LineDailyUsageReadResult usage,
            String workerId
    ) {
        // 1. Redis snapshot에 사용량이 하나도 없으면 DB insert 없이 SKIPPED로 닫고 skipped_count를 증가시킨다.
        if (!usage.hasAnyUsage()) {
            completeTarget(batchJobId, target, LineDailyBatchTargetStatus.SKIPPED, workerId);
            return;
        }

        // 2. 사용량이 있으면 Redis snapshot에 존재하는 사용량만 DB에 insert한다.
        insertUsages(target, usage);
        // 3. insert 이후 target row를 DONE으로 닫고 success_count를 증가시킨다.
        completeTarget(batchJobId, target, LineDailyBatchTargetStatus.DONE, workerId);
    }

    /**
     * 자동 복구가 불가능한 worker 실패를 FAILED terminal 상태로 닫고 failed_count를 증가시킨다.
     */
    @Transactional
    public void completeFailedTarget(Long batchJobId, LineDailyBatchTarget target, String workerId) {
        // 1. 현재 worker가 PROCESSING으로 보유한 target row를 FAILED로 닫고 failed_count를 증가시킨다.
        completeTarget(batchJobId, target, LineDailyBatchTargetStatus.FAILED, workerId);
    }

    /**
     * Redis snapshot에 존재하는 사용량만 각 DB 테이블에 insert한다.
     */
    private void insertUsages(LineDailyBatchTarget target, LineDailyUsageReadResult usage) {
        // 1. 일별 총 사용량이 있으면 DAILY_TOTAL_DATA에 insert한다.
        if (usage.totalUsageData() != null) {
            trafficDailyUsageBatchMapper.insertDailyTotalUsage(
                    target.getUsageDate(),
                    target.getLineId(),
                    usage.totalUsageData()
            );
        }

        // 2. 앱별 사용량 목록이 있으면 DAILY_APP_TOTAL_DATA에 multi-value insert한다.
        if (!usage.appUsages().isEmpty()) {
            List<DailyAppUsageBatchInsertRow> appUsages = usage.appUsages().stream()
                    .map(appUsage -> new DailyAppUsageBatchInsertRow(
                            appUsage.applicationId(),
                            appUsage.individualUsageData(),
                            appUsage.sharedUsageData(),
                            appUsage.qosUsageData()
                    ))
                    .toList();
            trafficDailyUsageBatchMapper.insertDailyAppUsages(
                    target.getUsageDate(),
                    target.getLineId(),
                    appUsages
            );
        }

        // 3. 공유풀 일별 사용량이 있으면 FAMILY_SHARED_USAGE_DAILY에 insert한다.
        if (usage.sharedUsage() != null) {
            trafficDailyUsageBatchMapper.insertFamilySharedDailyUsage(
                    target.getUsageDate(),
                    usage.sharedUsage().familyId(),
                    target.getLineId(),
                    usage.sharedUsage().usageAmount()
            );
        }
    }

    /**
     * 현재 worker가 PROCESSING으로 보유한 target row만 terminal 상태로 전환한 뒤 metadata count를 증가시킨다.
     */
    private void completeTarget(
            Long batchJobId,
            LineDailyBatchTarget target,
            LineDailyBatchTargetStatus terminalStatus,
            String workerId
    ) {
        // 1. target row id, PROCESSING 상태, worker_id 조건으로 terminal 상태 전환을 시도한다.
        int updated = lineDailyBatchTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                terminalStatus,
                workerId
        );
        // 2. target row 전환 affected rows가 1이 아니면 예외를 던져 트랜잭션을 롤백한다.
        if (updated != 1) {
            throw new IllegalStateException("Failed to complete processing target. targetId=" + target.getId());
        }

        // 3. terminal 상태에 맞는 usage sync batch count를 1 증가시킨다.
        int countUpdated = lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(batchJobId, terminalStatus);
        // 4. metadata count 증가 affected rows가 1이 아니면 예외를 던져 트랜잭션을 롤백한다.
        if (countUpdated != 1) {
            throw new IllegalStateException("Failed to increment usage sync count. batchJobId=" + batchJobId);
        }
    }
}
