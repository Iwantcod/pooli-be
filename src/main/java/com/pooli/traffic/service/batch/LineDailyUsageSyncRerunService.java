package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncRerunResDto;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * 운영자가 명시한 usage_date의 실패 target만 새 usage sync batch로 재처리합니다.
 * <p>
 * 기존 FAILED/ABANDONED batch row는 이력으로 보존하고,
 * 새 RUNNING metadata row를 만들어 이번 rerun의 count를 독립적으로 집계합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncRerunService {

    private final LineDailyBatchJobService lineDailyBatchJobService;
    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;
    private final LineDailyBatchWorkerScheduler lineDailyBatchWorkerScheduler;

    /**
     * 지정 usageDate의 최신 usage sync batch가 rerun 가능한 상태인지 확인하고 rerun을 접수한다.
     * 접수된 경우 FAILED target row만 RETRYABLE로 되돌린 뒤 commit 이후 worker 시작 감지를 호출한다.
     */
    @Transactional
    public LineDailyUsageSyncRerunResDto rerun(LocalDate usageDate) {
        // 1. 운영자가 지정한 날짜의 최신 usage sync batch 상태를 확인한다.
        LineDailyBatchJob previousBatch = lineDailyBatchJobService.findLatestUsageSyncBatch(usageDate);
        if (!isRerunnable(previousBatch)) {
            // 2. batch가 없거나 rerun 허용 상태가 아니면 새 row 생성 없이 거부 응답을 반환한다.
            return response(previousBatch, null, usageDate, 0L, false);
        }

        // 3. 이번 rerun에서 다시 처리할 FAILED target row 수를 새 batch target_count로 확정한다.
        long failedTargetCount = lineDailyBatchTargetMapper.countByUsageDateAndStatus(
                usageDate,
                LineDailyBatchTargetStatus.FAILED
        );
        // 4. 이전 batch row는 보존하고, 새 RUNNING usage sync batch row를 생성한다.
        LineDailyBatchJob rerunBatch =
                lineDailyBatchJobService.createRunningRerunUsageSyncBatch(usageDate, failedTargetCount);
        // 5. DONE/SKIPPED row는 유지하고 FAILED row만 worker claim 대상인 RETRYABLE로 되돌린다.
        lineDailyBatchTargetMapper.markFailedTargetsRetryableByUsageDate(usageDate);

        // 6. metadata와 target 상태 전환이 commit된 뒤 worker가 최신 RUNNING batch를 읽도록 예약한다.
        startWorkerAfterCommit(usageDate);
        return response(previousBatch, rerunBatch, usageDate, failedTargetCount, true);
    }

    /**
     * rerun 가능한 직전 batch 상태인지 판단한다.
     */
    private boolean isRerunnable(LineDailyBatchJob batchJob) {
        // 1. 기존 batch row가 있어야 rerun 기준 상태를 판단할 수 있다.
        return batchJob != null
                // 2. 운영 정책상 FAILED 또는 ABANDONED batch만 새 rerun batch 생성 대상이다.
                && (batchJob.getStatus() == LineDailyBatchStatus.FAILED
                || batchJob.getStatus() == LineDailyBatchStatus.ABANDONED);
    }

    /**
     * worker 시작 감지를 현재 transaction commit 이후에 실행한다.
     * commit 전 worker가 조회하면 새 RUNNING batch 또는 RETRYABLE target을 보지 못할 수 있다.
     */
    private void startWorkerAfterCommit(LocalDate usageDate) {
        // 1. 테스트나 비트랜잭션 호출 환경이면 즉시 worker 시작 감지를 호출한다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lineDailyBatchWorkerScheduler.startForUsageDate(usageDate);
            return;
        }

        // 2. 실제 @Transactional 경로에서는 DB 변경 commit 이후 worker 시작 감지를 실행한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lineDailyBatchWorkerScheduler.startForUsageDate(usageDate);
            }
        });
    }

    /**
     * rerun API 응답 DTO를 생성한다.
     * 거부 응답에서도 확인된 이전 batch 상태와 요청 usageDate를 함께 반환한다.
     */
    private LineDailyUsageSyncRerunResDto response(
            LineDailyBatchJob previousBatch,
            LineDailyBatchJob rerunBatch,
            LocalDate usageDate,
            Long targetCount,
            boolean rerunAccepted
    ) {
        // 1. 승인/거부 공통 응답 필드를 구성한다.
        return LineDailyUsageSyncRerunResDto.builder()
                // 2. batch가 없던 요청은 null로 반환해 운영자가 원인을 구분할 수 있게 한다.
                .previousBatchJobId(previousBatch == null ? null : previousBatch.getId())
                .rerunBatchJobId(rerunBatch == null ? null : rerunBatch.getId())
                .usageDate(usageDate)
                .previousStatus(previousBatch == null ? null : previousBatch.getStatus())
                .targetCount(targetCount)
                .rerunAccepted(rerunAccepted)
                .build();
    }
}
