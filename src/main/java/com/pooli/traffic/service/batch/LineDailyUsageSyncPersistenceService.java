package com.pooli.traffic.service.batch;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.mapper.DailyAppUsageBatchInsertRow;
import com.pooli.traffic.mapper.DailySharedUsageBatchInsertRow;
import com.pooli.traffic.mapper.DailyTotalUsageBatchInsertRow;
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

    /**
     * MAX_TARGET_RETRY_COUNT는 target 영속화 작업의 최대 재시도 횟수를 제어합니다.
     * 값 10은 일시적 장애 복구를 위한 재시도 적극성과 무한 재시도로 인한 시스템 부하 방지 간의 균형(tradeoff)을 고려해 선택되었습니다.
     * 이 값을 늘리면 지속적 장애 시 worker 리소스 낭비 및 부하를 유의해야 하며, 줄이면 간헐적 오류에도 타겟이 쉽게 실패 처리될 수 있음을 주의해야 합니다.
     */
    static final int MAX_TARGET_RETRY_COUNT = 10;

    /**
     * LAST_ERROR_MESSAGE_MAX_LENGTH는 DB에 저장되는 에러 메시지의 최대 길이(truncation limit)를 제어합니다.
     * 값 512는 불필요한 스택 트레이스로 인한 DB 용량 낭비를 막으면서 디버깅에 필요한 핵심 내용만 남기기에 합리적인 크기(reasonable log size)로 선택되었습니다.
     * 이 값을 늘리면 DB 스토리지 용량 증가와 컬럼 최대 길이를 확인해야 하며, 줄이면 중요한 에러 원인이 잘려 문제 파악이 어려워질 수 있음을 주의해야 합니다.
     */
    private static final int LAST_ERROR_MESSAGE_MAX_LENGTH = 512;

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
     * Redis 조회가 성공한 target chunk 전체를 한 트랜잭션에서 insert하고 DONE/SKIPPED terminal 상태로 닫는다.
     */
    @Transactional
    public void persistUsagesAndCompleteTargets(
            LineDailyBatchJob batchJob,
            List<LineDailyTargetWithSnapshot> snapshots,
            String workerId
    ) {
        // 1. 처리할 snapshot이 없으면 DB write 없이 종료한다.
        if (snapshots.isEmpty()) {
            return;
        }

        List<Long> doneIds = new ArrayList<>();
        List<Long> skippedIds = new ArrayList<>();
        List<DailyTotalUsageBatchInsertRow> totalUsageRows = new ArrayList<>();
        List<DailyAppUsageBatchInsertRow> appUsageRows = new ArrayList<>();
        List<DailySharedUsageBatchInsertRow> sharedUsageRows = new ArrayList<>();

        // 2. Redis snapshot 존재 여부에 따라 target terminal 상태와 테이블별 insert row를 분류한다.
        for (LineDailyTargetWithSnapshot targetWithSnapshot : snapshots) {
            LineDailyBatchTarget target = targetWithSnapshot.target();
            LineDailyUsageReadResult usage = targetWithSnapshot.snapshot();
            if (!usage.hasAnyUsage()) {
                skippedIds.add(target.getId());
                continue;
            }

            doneIds.add(target.getId());
            collectUsageRows(target, usage, totalUsageRows, appUsageRows, sharedUsageRows);
        }

        // 3. 테이블별 사용량 row를 multi-value insert로 저장한다.
        insertUsageRows(totalUsageRows, appUsageRows, sharedUsageRows);
        // 4. insert가 모두 성공한 뒤 현재 worker가 보유한 target row만 terminal 상태로 닫는다.
        markTargetsTerminalInBulk(doneIds, LineDailyBatchTargetStatus.DONE, workerId);
        markTargetsTerminalInBulk(skippedIds, LineDailyBatchTargetStatus.SKIPPED, workerId);
        // 5. target terminal 전환 수와 같은 DONE/SKIPPED metadata count를 한 번에 반영한다.
        incrementUsageSyncSuccessAndSkippedCount(batchJob.getId(), doneIds.size(), skippedIds.size());
    }

    /**
     * 자동 복구가 불가능한 worker 실패를 FAILED terminal 상태로 닫고 failed_count를 증가시킨다.
     */
    @Transactional
    public void completeFailedTarget(Long batchJobId, LineDailyBatchTarget target, String workerId) {
        // 1. 현재 worker가 PROCESSING으로 보유한 target row를 FAILED로 닫고 failed_count를 증가시킨다.
        completeFailedTarget(batchJobId, target, workerId, "WORKER_FAILED", null);
    }

    /**
     * 재시도 가능한 worker 실패를 retry_count 기준으로 RETRYABLE 또는 FAILED로 기록한다.
     */
    @Transactional
    public void recordRetryableFailure(
            Long batchJobId,
            LineDailyBatchTarget target,
            String workerId,
            String lastErrorCode,
            String lastErrorMessage
    ) {
        // 1. RETRYABLE로 되돌린 횟수가 한도 미만이면 count 증가 없이 다시 선점 가능한 상태로 되돌린다.
        if (currentRetryCount(target) < MAX_TARGET_RETRY_COUNT) {
            int updated = lineDailyBatchTargetMapper.markTargetRetryableIfProcessing(
                    target.getId(),
                    workerId,
                    MAX_TARGET_RETRY_COUNT,
                    lastErrorCode,
                    truncateLastErrorMessage(lastErrorMessage)
            );
            if (updated != 1) {
                throw new IllegalStateException("Failed to mark retryable target. targetId=" + target.getId());
            }
            return;
        }

        // 2. 이미 RETRYABLE 전환 횟수가 한도에 도달한 row는 retry_count 증가 없이 FAILED로 닫는다.
        completeFailedTarget(batchJobId, target, workerId, "RETRY_EXHAUSTED", lastErrorMessage);
    }

    /**
     * 자동 복구가 불가능한 worker 실패를 retry_count와 무관하게 FAILED로 기록한다.
     */
    @Transactional
    public void recordNonRetryableFailure(
            Long batchJobId,
            LineDailyBatchTarget target,
            String workerId,
            String lastErrorCode,
            String lastErrorMessage
    ) {
        completeFailedTarget(batchJobId, target, workerId, lastErrorCode, lastErrorMessage);
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
                            target.getUsageDate(),
                            target.getLineId(),
                            appUsage.applicationId(),
                            appUsage.individualUsageData(),
                            appUsage.sharedUsageData(),
                            appUsage.qosUsageData()
                    ))
                    .toList();
            trafficDailyUsageBatchMapper.insertDailyAppUsages(appUsages);
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
     * 한 target의 Redis snapshot을 테이블별 bulk insert row 목록으로 변환해 누적한다.
     */
    private void collectUsageRows(
            LineDailyBatchTarget target,
            LineDailyUsageReadResult usage,
            List<DailyTotalUsageBatchInsertRow> totalUsageRows,
            List<DailyAppUsageBatchInsertRow> appUsageRows,
            List<DailySharedUsageBatchInsertRow> sharedUsageRows
    ) {
        // 1. 존재하는 총 사용량만 DAILY_TOTAL_DATA bulk row로 누적한다.
        if (usage.totalUsageData() != null) {
            totalUsageRows.add(new DailyTotalUsageBatchInsertRow(
                    target.getUsageDate(),
                    target.getLineId(),
                    usage.totalUsageData()
            ));
        }

        // 2. 존재하는 앱별 사용량만 DAILY_APP_TOTAL_DATA bulk row로 누적한다.
        for (DailyAppUsage appUsage : usage.appUsages()) {
            appUsageRows.add(new DailyAppUsageBatchInsertRow(
                    target.getUsageDate(),
                    target.getLineId(),
                    appUsage.applicationId(),
                    appUsage.individualUsageData(),
                    appUsage.sharedUsageData(),
                    appUsage.qosUsageData()
            ));
        }

        // 3. 존재하는 공유풀 사용량만 FAMILY_SHARED_USAGE_DAILY bulk row로 누적한다.
        if (usage.sharedUsage() != null) {
            sharedUsageRows.add(new DailySharedUsageBatchInsertRow(
                    target.getUsageDate(),
                    usage.sharedUsage().familyId(),
                    target.getLineId(),
                    usage.sharedUsage().usageAmount()
            ));
        }
    }

    /**
     * 비어 있지 않은 테이블별 row 목록만 bulk insert mapper로 전달한다.
     */
    private void insertUsageRows(
            List<DailyTotalUsageBatchInsertRow> totalUsageRows,
            List<DailyAppUsageBatchInsertRow> appUsageRows,
            List<DailySharedUsageBatchInsertRow> sharedUsageRows
    ) {
        // 1. 총 사용량, 앱별 사용량, 공유풀 사용량 순서로 insert해 기존 단건 처리 순서를 유지한다.
        if (!totalUsageRows.isEmpty()) {
            trafficDailyUsageBatchMapper.insertDailyTotalUsages(totalUsageRows);
        }
        if (!appUsageRows.isEmpty()) {
            trafficDailyUsageBatchMapper.insertDailyAppUsages(appUsageRows);
        }
        if (!sharedUsageRows.isEmpty()) {
            trafficDailyUsageBatchMapper.insertFamilySharedDailyUsages(sharedUsageRows);
        }
    }

    /**
     * 현재 worker가 PROCESSING으로 보유한 target row 목록을 기대 건수와 일치할 때만 terminal 상태로 닫는다.
     */
    private void markTargetsTerminalInBulk(
            List<Long> targetIds,
            LineDailyBatchTargetStatus terminalStatus,
            String workerId
    ) {
        // 1. 해당 terminal 상태로 전환할 target이 없으면 mapper 호출 없이 종료한다.
        if (targetIds.isEmpty()) {
            return;
        }

        // 2. 현재 worker가 선점한 PROCESSING row만 terminal 상태로 전환한다.
        int updated = lineDailyBatchTargetMapper.markTargetsTerminalInBulk(
                targetIds,
                terminalStatus,
                workerId
        );
        // 3. 일부 row라도 전환되지 않으면 count 불일치를 막기 위해 트랜잭션을 롤백한다.
        if (updated != targetIds.size()) {
            throw new IllegalStateException(
                    "Failed to complete processing targets. status=" + terminalStatus
                            + ", expected=" + targetIds.size()
                            + ", actual=" + updated
            );
        }
    }

    /**
     * bulk terminal 전환 결과와 같은 수만큼 usage sync metadata count를 증가시킨다.
     */
    private void incrementUsageSyncSuccessAndSkippedCount(
            Long batchJobId,
            int successDelta,
            int skippedDelta
    ) {
        // 1. bulk 처리 target 수가 0이면 count 변경이 필요 없으므로 종료한다.
        if (successDelta == 0 && skippedDelta == 0) {
            return;
        }

        // 2. RUNNING usage sync metadata row의 DONE/SKIPPED count를 한 번에 증가시킨다.
        int countUpdated = lineDailyBatchJobMapper.incrementUsageSyncSuccessAndSkippedCount(
                batchJobId,
                successDelta,
                skippedDelta
        );
        // 3. metadata row가 갱신되지 않으면 target terminal 전환과 함께 롤백한다.
        if (countUpdated != 1) {
            throw new IllegalStateException("Failed to increment usage sync count. batchJobId=" + batchJobId);
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

    /**
     * 현재 worker가 PROCESSING으로 보유한 target row만 FAILED로 전환한 뒤 failed_count를 증가시킨다.
     */
    private void completeFailedTarget(
            Long batchJobId,
            LineDailyBatchTarget target,
            String workerId,
            String lastErrorCode,
            String lastErrorMessage
    ) {
        int updated = lineDailyBatchTargetMapper.markTargetFailedIfProcessing(
                target.getId(),
                workerId,
                lastErrorCode,
                truncateLastErrorMessage(lastErrorMessage)
        );
        if (updated != 1) {
            throw new IllegalStateException("Failed to mark failed target. targetId=" + target.getId());
        }

        int countUpdated = lineDailyBatchJobMapper.incrementUsageSyncProcessedCount(
                batchJobId,
                LineDailyBatchTargetStatus.FAILED
        );
        if (countUpdated != 1) {
            throw new IllegalStateException("Failed to increment usage sync count. batchJobId=" + batchJobId);
        }
    }

    private int currentRetryCount(LineDailyBatchTarget target) {
        return target.getRetryCount() == null ? 0 : target.getRetryCount();
    }

    private String truncateLastErrorMessage(String lastErrorMessage) {
        if (lastErrorMessage == null || lastErrorMessage.length() <= LAST_ERROR_MESSAGE_MAX_LENGTH) {
            return lastErrorMessage;
        }
        return lastErrorMessage.substring(0, LAST_ERROR_MESSAGE_MAX_LENGTH);
    }
}
