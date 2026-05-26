package com.pooli.traffic.service.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

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
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

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
            processTarget(batchJob, target, workerId);
        }
    }

    /**
     * target row 하나를 Redis 조회부터 MySQL 반영까지 처리하고 실패 시 retry 정책에 맞게 상태를 기록한다.
     */
    private void processTarget(LineDailyBatchJob batchJob, LineDailyBatchTarget target, String workerId) {
        try {
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
        } catch (RuntimeException e) {
            recordTargetFailure(batchJob, target, workerId, e);
        }
    }

    /**
     * 재시도 가능한 Redis/DB 인프라 장애만 RETRYABLE 대상으로 분류하고 나머지는 FAILED로 종결한다.
     */
    private void recordTargetFailure(
            LineDailyBatchJob batchJob,
            LineDailyBatchTarget target,
            String workerId,
            RuntimeException exception
    ) {
        String errorMessage = summarizeException(exception);
        if (isRetryableFailure(exception)) {
            log.warn(
                    "line_daily_usage_sync_worker_retryable_failure targetId={} usageDate={} lineId={} retryCount={} error={}",
                    target.getId(),
                    target.getUsageDate(),
                    target.getLineId(),
                    target.getRetryCount(),
                    errorMessage,
                    exception
            );
            lineDailyUsageSyncPersistenceService.recordRetryableFailure(
                    batchJob.getId(),
                    target,
                    workerId,
                    "RETRYABLE_WORKER_FAILURE",
                    errorMessage
            );
            return;
        }

        log.error(
                "line_daily_usage_sync_worker_non_retryable_failure targetId={} usageDate={} lineId={} error={}",
                target.getId(),
                target.getUsageDate(),
                target.getLineId(),
                errorMessage,
                exception
        );
        lineDailyUsageSyncPersistenceService.recordNonRetryableFailure(
                batchJob.getId(),
                target,
                workerId,
                "NON_RETRYABLE_WORKER_FAILURE",
                errorMessage
        );
    }

    private boolean isRetryableFailure(RuntimeException exception) {
        return trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)
                || isRetryableDbException(exception);
    }

    private boolean isRetryableDbException(DataAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || current instanceof CannotAcquireLockException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof ConcurrencyFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isRetryableDbException(RuntimeException exception) {
        return exception instanceof DataAccessException dataAccessException
                && isRetryableDbException(dataAccessException);
    }

    private String summarizeException(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private String buildWorkerId(LocalDate usageDate) {
        return "line-daily-worker:" + usageDate + ":" + UUID.randomUUID();
    }
}
