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
        // 2. 복구 진행 도중 실시간 트래픽을 차단하기 위해 복구 flag를 활성화한다.
        policyFlagService.activateRestoreFlag();
        // 3. 온디맨드로 정책 데이터 기화(hydration)를 수동 수행한다.
        policyBootstrapService.hydrateOnDemand();
        // 4. 실시간으로 처리 중이던 메시지가 안전하게 쓰여질 때까지 최대 소요시간+버퍼만큼 대기한다.
        waitService.waitWorstProcessingTimePlusBuffer();
        // 5. 대상 기간(월별)을 추출하여 순차적으로 배치 복구 프로세스(Phase 0~2)를 기동한다.
        executionService.execute(
                failureDate,
                restoreStartDate,
                resolveTargetMonths(restoreStartDate, failureDate)
        );
        // 6. 모든 복구 프로세스가 종료되면 복구 flag를 비활성화하여 실시간 처리를 재개한다.
        policyFlagService.deactivateRestoreFlag();
        // 7. 복구가 정상적으로 완료되었음을 가리키는 응답 DTO를 생성하여 반환한다.
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
        // 1. 입력된 기준일(anchorDate)의 최신 복구 배치 작업(Phase 0~2) 이력을 조회한다.
        LineDailyBatchJob resumableJob = findResumableRestorePhase(anchorDate);
        // 2. 작업 이력이 존재하지 않거나 상태가 기재되어 있지 않으면 재개 불가(NOT_FOUND)로 실패 처리한다.
        if (resumableJob == null || resumableJob.getStatus() == null) {
            return new TrafficRestoreResumeResDto(anchorDate, false, "NOT_FOUND");
        }
        // 3. 배치의 최종 상태가 실패(FAILED) 또는 포기(ABANDONED)인지 확인하여 분기를 처리한다.
        boolean resumeAccepted = switch (resumableJob.getStatus()) {
            case FAILED, ABANDONED -> {
                // 3-1. 실패했던 타겟 데이터들의 상태를 다시 시도 가능(RETRYABLE)으로 되돌려 복원한다.
                resetFailedTargets(resumableJob.getBatchName(), anchorDate);
                // 3-2. 실패한 배치의 메타데이터 상태를 관리자 재개(admin-resume) 권한 명의로 변경하여 배치를 재시작한다.
                batchJobMapper.restartRestorePhaseBatch(
                        resumableJob.getId(),
                        resumableJob.getBatchName(),
                        "admin-resume"
                );
                yield true;
            }
            default -> false;
        };
        // 4. 재개 수락 성공 여부와 기존 상태 명칭을 취합하여 최종 DTO를 반환한다.
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
