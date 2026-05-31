package com.pooli.traffic.service.restore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pooli.traffic.config.TrafficRestoreProperties;
import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.RestoreRange;
import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * Redis 장애 복구 phase들을 실제 Redis 반영 작업까지 순차 실행한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestoreExecutionService {

    private static final String START_API_WORKER_ID = "restore-start-api";

    private final TrafficRestoreProperties trafficRestoreProperties;
    private final TrafficRestorePhase0TargetInsertService phase0TargetInsertService;
    private final TrafficRestoreHydrateTargetMapper hydrateTargetMapper;
    private final TrafficRestorePhase0HydrateService phase0HydrateService;
    private final TrafficRestorePhase1TargetInsertService phase1TargetInsertService;
    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;
    private final TrafficRestorePhase1ReplayService phase1ReplayService;
    private final TrafficRestorePhase2ReplayService phase2ReplayService;
    private final TrafficRestoreVerificationService verificationService;

    /**
     * 계산된 복구 범위에 대해 hydrate, daily app replay, done log replay, 검증/보정을 순서대로 수행한다.
     */
    public void execute(LocalDate failureDate, LocalDate restoreStartDate, List<YearMonth> targetMonths) {
        RestoreRange restoreRange = new RestoreRange(restoreStartDate, failureDate.plusDays(1));

        // 1. Redis 잔량/정책 snapshot을 복구할 target을 만들고 모두 hydrate한다.
        phase0TargetInsertService.insertTargets(
                BatchName.RESTORE_P0_REDIS_HYDRATE,
                failureDate,
                restoreStartDate,
                targetMonths
        );
        drainPhase0HydrateTargets();

        // 2. 일별 동기화 DB 원천 데이터가 있는 경우 Redis 사용량/잔량을 replay한다.
        phase1TargetInsertService.insertTargets(
                BatchName.RESTORE_P1_DAILY_APP_REPLAY,
                restoreStartDate,
                failureDate
        );
        drainPhase1DailyAppTargets();

        // 3. 아직 일별 동기화에 반영되지 않은 done log를 replay한 뒤 전체 Redis 값을 DB 기준으로 검증한다.
        drainPhase2DoneLogs(restoreRange);
        verificationService.verifyAndCorrect(failureDate, restoreRange);
    }

    private void drainPhase0HydrateTargets() {
        while (true) {
            List<TrafficRestoreHydrateTarget> targets = hydrateTargetMapper.selectClaimableTargetsForUpdate(
                    BatchName.RESTORE_P0_REDIS_HYDRATE.name(),
                    leaseExpiredBefore(),
                    trafficRestoreProperties.getWorkerChunkSize()
            );
            if (targets.isEmpty()) {
                return;
            }

            hydrateTargetMapper.markTargetsProcessing(targetIds(targets), START_API_WORKER_ID);
            for (TrafficRestoreHydrateTarget target : targets) {
                phase0HydrateService.hydrate(target);
                hydrateTargetMapper.markTargetTerminalIfProcessing(
                        target.getId(),
                        TrafficRestoreTargetStatus.DONE,
                        START_API_WORKER_ID
                );
            }
        }
    }

    private void drainPhase1DailyAppTargets() {
        String workerId = START_API_WORKER_ID;
        while (true) {
            List<TrafficRestoreDailyAppTarget> targets = dailyAppTargetMapper.selectClaimableTargetsForUpdate(
                    BatchName.RESTORE_P1_DAILY_APP_REPLAY.name(),
                    leaseExpiredBefore(),
                    trafficRestoreProperties.getWorkerChunkSize()
            );
            if (targets.isEmpty()) {
                return;
            }

            dailyAppTargetMapper.markTargetsProcessing(dailyAppTargetIds(targets), workerId);
            for (TrafficRestoreDailyAppTarget target : targets) {
                phase1ReplayService.replay(target, workerId);
            }
        }
    }

    private void drainPhase2DoneLogs(RestoreRange restoreRange) {
        String workerId = START_API_WORKER_ID;
        while (true) {
            List<TrafficDeductDoneLog> logs = phase2ReplayService.claim(
                    restoreRange.startDateTimeInclusive(),
                    restoreRange.endDateTimeExclusive(),
                    leaseExpiredBefore(),
                    workerId,
                    trafficRestoreProperties.getWorkerChunkSize()
            );
            if (logs.isEmpty()) {
                return;
            }

            for (TrafficDeductDoneLog log : logs) {
                phase2ReplayService.replay(log, workerId);
            }
        }
    }

    private LocalDateTime leaseExpiredBefore() {
        return LocalDateTime.now().minusSeconds(trafficRestoreProperties.getProcessingLeaseTimeoutSeconds());
    }

    private List<Long> targetIds(List<TrafficRestoreHydrateTarget> targets) {
        return targets.stream()
                .map(TrafficRestoreHydrateTarget::getId)
                .toList();
    }

    private List<Long> dailyAppTargetIds(List<TrafficRestoreDailyAppTarget> targets) {
        return targets.stream()
                .map(TrafficRestoreDailyAppTarget::getId)
                .toList();
    }
}
