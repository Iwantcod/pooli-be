package com.pooli.traffic.service.restore;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreResumeResDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreStartResDto;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 Redis 복구 시작/재개 API의 phase orchestration을 담당한다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreOrchestratorService {

    static final long RESTORE_MANAGER_LOCK_TTL_MS = 6 * 60 * 60 * 1000L;

    private final TrafficRestorePolicyFlagService policyFlagService;
    private final TrafficPolicyBootstrapService policyBootstrapService;
    private final TrafficRestoreWaitService waitService;
    private final TrafficRestoreExecutionService executionService;
    private final TrafficRestoreStartDateResolver startDateResolver;
    private final LineDailyBatchJobMapper batchJobMapper;
    private final TrafficRestoreHydrateTargetMapper hydrateTargetMapper;
    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;
    private final TrafficDeductDoneLogMapper doneLogMapper;
    private final Executor taskExecutor;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    /**
     * 복구 worker를 background로 접수하고 중복 시작을 Redis lock으로 차단한다.
     */
    public TrafficRestoreStartResDto start(TrafficRestoreStartReqDto request) {
        // 1. 장애일 기준 복구 시작일을 결정하고 복구 대상 존재 여부를 유효성 검사한다.
        LocalDate failureDate = request.failureDate();
        LocalDate restoreStartDate = startDateResolver.resolve(failureDate);
        if (restoreStartDate.isAfter(failureDate)) {
            return new TrafficRestoreStartResDto(
                    false,
                    "NO_RESTORE_TARGET",
                    failureDate,
                    restoreStartDate
            );
        }
        // 2. 복구 worker 중복 실행을 막기 위해 단일 Redis manager lock 획득을 시도한다.
        RestoreLock restoreLock = tryAcquireRestoreLock();
        if (restoreLock == null) {
            return new TrafficRestoreStartResDto(
                    false,
                    "RESTORE_ALREADY_RUNNING",
                    failureDate,
                    restoreStartDate
            );
        }
        // 3. 요청 thread는 접수 응답만 반환하고, 실제 복구 lifecycle은 background worker에서 관리한다.
        try {
            taskExecutor.execute(() -> runRestoreWorker(failureDate, restoreStartDate, restoreLock));
        } catch (RuntimeException e) {
            releaseRestoreLock(restoreLock);
            throw e;
        }
        return new TrafficRestoreStartResDto(
                true,
                "RESTORE_ACCEPTED",
                failureDate,
                restoreStartDate
        );
    }

    /**
     * anchor date 기준 phase 2 metadata 상태를 확인해 운영자가 재개 가능 여부를 판단할 수 있게 반환한다.
     */
    public TrafficRestoreResumeResDto resume(LocalDate anchorDate) {
        // 1. 입력된 기준일(anchorDate)의 최신 복구 배치 작업(Phase 0~2) 이력을 조회한다.
        LineDailyBatchJob resumableJob = findResumableRestorePhase(anchorDate);
        // 2. 작업 이력이 존재하지 않거나 상태가 기재되어 있지 않으면 재개 불가(NOT_FOUND)로 실패 처리한다.
        if (resumableJob == null || resumableJob.getStatus() == null) {
            return new TrafficRestoreResumeResDto(anchorDate, false, "NOT_FOUND");
        }
        // 3. FAILED/ABANDONED terminal 상태만 운영자 재개 대상으로 인정한다.
        if (resumableJob.getStatus() != LineDailyBatchStatus.FAILED
                && resumableJob.getStatus() != LineDailyBatchStatus.ABANDONED) {
            return new TrafficRestoreResumeResDto(anchorDate, false, resumableJob.getStatus().name());
        }
        // 4. start와 같은 Redis lock으로 재개 worker 중복 실행을 차단한다.
        RestoreLock restoreLock = tryAcquireRestoreLock();
        if (restoreLock == null) {
            return new TrafficRestoreResumeResDto(anchorDate, false, "RESTORE_ALREADY_RUNNING");
        }

        try {
            // 5. 실패했던 타겟 데이터들의 상태를 다시 시도 가능(RETRYABLE)으로 되돌려 복원한다.
            resetFailedTargets(resumableJob.getBatchName(), anchorDate);
            // 6. 실패한 배치 metadata를 RUNNING으로 열고, CAS 결과가 실패하면 lock을 해제하고 재개를 거절한다.
            int updated = batchJobMapper.restartRestorePhaseBatch(
                    resumableJob.getId(),
                    resumableJob.getBatchName(),
                    "admin-resume"
            );
            if (updated != 1) {
                releaseRestoreLock(restoreLock);
                return new TrafficRestoreResumeResDto(anchorDate, false, resumableJob.getStatus().name());
            }
            // 7. 재개도 동일한 background worker lifecycle로 실행해 flag 정리를 단일 경로로 보장한다.
            taskExecutor.execute(() -> runRestoreWorker(anchorDate, anchorDate, restoreLock));
            return new TrafficRestoreResumeResDto(anchorDate, true, resumableJob.getStatus().name());
        } catch (RuntimeException e) {
            releaseRestoreLock(restoreLock);
            throw e;
        }
    }

    private LineDailyBatchJob findResumableRestorePhase(LocalDate anchorDate) {
        for (BatchName batchName : List.of(
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                BatchName.RESTORE_P1_DAILY_APP_REPLAY,
                BatchName.RESTORE_P0_REDIS_HYDRATE
        )) {
            LineDailyBatchJob job = batchJobMapper.selectLatestByBatchNameAndUsageDate(batchName, anchorDate);
            if (job != null) {
                return job;
            }
        }
        return null;
    }

    private void resetFailedTargets(BatchName batchName, LocalDate anchorDate) {
        switch (batchName) {
            case RESTORE_P0_REDIS_HYDRATE -> hydrateTargetMapper.resetFailedTargetsToRetryable(batchName.name());
            case RESTORE_P1_DAILY_APP_REPLAY -> dailyAppTargetMapper.resetFailedTargetsToRetryable(batchName.name());
            case RESTORE_P2_DONE_LOG_REPLAY -> doneLogMapper.resetFailedRestoreLogsToRetryable(
                    anchorDate.plusDays(1).atStartOfDay()
            );
            default -> {
                // target이 없는 phase는 metadata 재시작만 수행한다.
            }
        }
    }

    private void runRestoreWorker(LocalDate failureDate, LocalDate restoreStartDate, RestoreLock restoreLock) {
        try {
            // 1. 복구 worker가 실제 작업을 시작할 때 traffic 진입 차단 flag를 활성화한다.
            policyFlagService.activateRestoreFlag();
            // 2. 정책 snapshot을 Redis에 먼저 재적재해 flag와 정책 조회가 같은 Redis 상태를 보도록 맞춘다.
            policyBootstrapService.hydrateOnDemand();
            // 3. 이미 처리 중이던 stream message가 기록될 시간을 흡수한 뒤 phase replay를 시작한다.
            waitService.waitWorstProcessingTimePlusBuffer();
            // 4. 대상 기간(월별)을 추출하여 복구 phase 0~2와 검증/보정을 순차 실행한다.
            executionService.execute(
                    failureDate,
                    restoreStartDate,
                    resolveTargetMonths(restoreStartDate, failureDate)
            );
        } finally {
            cleanupRestoreWorker(restoreLock);
        }
    }

    private void cleanupRestoreWorker(RestoreLock restoreLock) {
        try {
            policyFlagService.deactivateRestoreFlag();
        } catch (Exception e) {
            log.warn("traffic_restore_flag_deactivate_failed", e);
        } finally {
            releaseRestoreLock(restoreLock);
        }
    }

    private RestoreLock tryAcquireRestoreLock() {
        String lockKey = trafficRedisKeyFactory.trafficRestoreManagerLockKey();
        String owner = "traffic-restore:" + UUID.randomUUID();
        Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                owner,
                Duration.ofMillis(RESTORE_MANAGER_LOCK_TTL_MS)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }
        return new RestoreLock(lockKey, owner);
    }

    private void releaseRestoreLock(RestoreLock restoreLock) {
        try {
            trafficLuaScriptInfraService.executeLockRelease(restoreLock.lockKey(), restoreLock.owner());
        } catch (Exception e) {
            log.warn("traffic_restore_lock_release_failed lockKey={}", restoreLock.lockKey(), e);
        }
    }

    private List<YearMonth> resolveTargetMonths(LocalDate restoreStartDate, LocalDate failureDate) {
        YearMonth startMonth = YearMonth.from(restoreStartDate);
        YearMonth endMonth = YearMonth.from(failureDate);
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = startMonth;
        while (!cursor.isAfter(endMonth)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }

    private record RestoreLock(String lockKey, String owner) {
    }
}
