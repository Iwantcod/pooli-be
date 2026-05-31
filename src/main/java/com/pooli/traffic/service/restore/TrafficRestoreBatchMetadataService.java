package com.pooli.traffic.service.restore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase metadata 완료 조건과 상태 전환을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestoreBatchMetadataService {

    private final TrafficRestoreHydrateTargetMapper hydrateTargetMapper;
    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;
    private final LineDailyBatchJobMapper lineDailyBatchJobMapper;

    /**
     * target이 모두 DONE이고 FAILED가 없을 때만 RUNNING phase를 COMPLETED로 전환한다.
     */
    @Transactional
    public boolean completePhaseIfAllTargetsDone(LineDailyBatchJob batchJob) {
        BatchName batchName = batchJob.getBatchName();
        String batchNameValue = batchName.name();

        // 1. FAILED target이 있으면 운영자 확인 전 다음 phase로 넘어가지 않는다.
        if (countFailedTargets(batchName, batchNameValue) > 0L) {
            return false;
        }

        // 2. DONE이 아닌 target이 남아 있으면 현재 phase를 계속 RUNNING으로 둔다.
        if (countNotDoneTargets(batchName, batchNameValue) > 0L) {
            return false;
        }

        // 3. target 검증이 끝난 뒤 metadata row를 CAS성 조건으로 COMPLETED 처리한다.
        int updated = lineDailyBatchJobMapper.completeRunningRestorePhaseBatch(batchJob.getId(), batchName);
        return updated == 1;
    }

    private long countFailedTargets(BatchName batchName, String batchNameValue) {
        if (batchName == BatchName.RESTORE_P1_DAILY_APP_REPLAY) {
            return dailyAppTargetMapper.countFailedTargets(batchNameValue);
        }
        return hydrateTargetMapper.countFailedTargets(batchNameValue);
    }

    private long countNotDoneTargets(BatchName batchName, String batchNameValue) {
        if (batchName == BatchName.RESTORE_P1_DAILY_APP_REPLAY) {
            return dailyAppTargetMapper.countNotDoneTargets(batchNameValue);
        }
        return hydrateTargetMapper.countNotDoneTargets(batchNameValue);
    }
}
