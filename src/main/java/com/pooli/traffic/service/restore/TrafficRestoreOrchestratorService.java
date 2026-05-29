package com.pooli.traffic.service.restore;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreResumeResDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreStartResDto;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 Redis 복구 시작/재개 API의 phase orchestration을 담당한다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreOrchestratorService {

    private final TrafficRestorePolicyFlagService policyFlagService;
    private final TrafficPolicyBootstrapService policyBootstrapService;
    private final TrafficRestoreWaitService waitService;
    private final TrafficRestoreExecutionService executionService;
    private final TrafficRestoreStartDateResolver startDateResolver;
    private final LineDailyBatchJobMapper batchJobMapper;
    private final TrafficRestoreHydrateTargetMapper hydrateTargetMapper;
    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;
    private final TrafficDeductDoneLogMapper doneLogMapper;

    /**
     * 복구 flag를 활성화한 뒤 phase 0 target insert를 시작한다.
     */
    public TrafficRestoreStartResDto start(TrafficRestoreStartReqDto request) {
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
        policyFlagService.activateRestoreFlag();
        policyBootstrapService.hydrateOnDemand();
        waitService.waitWorstProcessingTimePlusBuffer();
        executionService.execute(
                failureDate,
                restoreStartDate,
                resolveTargetMonths(restoreStartDate, failureDate)
        );
        policyFlagService.deactivateRestoreFlag();
        return new TrafficRestoreStartResDto(
                true,
                "RESTORE_COMPLETED",
                failureDate,
                restoreStartDate
        );
    }

    /**
     * anchor date 기준 phase 2 metadata 상태를 확인해 운영자가 재개 가능 여부를 판단할 수 있게 반환한다.
     */
    public TrafficRestoreResumeResDto resume(LocalDate anchorDate) {
        LineDailyBatchJob resumableJob = findResumableRestorePhase(anchorDate);
        if (resumableJob == null || resumableJob.getStatus() == null) {
            return new TrafficRestoreResumeResDto(anchorDate, false, "NOT_FOUND");
        }
        boolean resumeAccepted = switch (resumableJob.getStatus()) {
            case FAILED, ABANDONED -> {
                resetFailedTargets(resumableJob.getBatchName(), anchorDate);
                batchJobMapper.restartRestorePhaseBatch(
                        resumableJob.getId(),
                        resumableJob.getBatchName(),
                        "admin-resume"
                );
                yield true;
            }
            default -> false;
        };
        return new TrafficRestoreResumeResDto(anchorDate, resumeAccepted, resumableJob.getStatus().name());
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
}
