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
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;
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
    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;
    private final LineDailyBatchJobService lineDailyBatchJobService;

    /**
     * worker cycle 하나를 실행한다.
     * target row를 chunk 단위로 선점하고, 선점 결과에 따라 즉시 재실행/empty poll 대기/종료 신호를 반환한다.
     */
    public LineDailyUsageSyncWorkerRunResult run(LineDailyBatchJob batchJob) {
        // 1. 이번 worker cycle에서 선점 상태를 소유할 workerId를 usage_date 단위로 생성한다.
        String workerId = buildWorkerId(batchJob.getUsageDate());
        // 2. 같은 usage_date의 claimable target row를 정해진 chunk 크기만큼 선점한다.
        List<LineDailyBatchTarget> claimedTargets = lineDailyBatchTargetClaimService.claim(
                batchJob.getUsageDate(),
                workerId,
                WORKER_CLAIM_CHUNK_SIZE
        );

        // 3. worker별 선점 결과를 남겨 empty poll과 정상 처리 흐름을 운영 로그에서 구분할 수 있게 한다.
        log.info(
                "line_daily_usage_sync_worker_claimed batchJobId={} usageDate={} workerId={} claimedCount={}",
                batchJob.getId(),
                batchJob.getUsageDate(),
                workerId,
                claimedTargets.size()
        );

        // 4. 선점 row가 없으면 완료 판단 또는 empty poll 재예약 판단으로 분기한다.
        if (claimedTargets.isEmpty()) {
            return handleEmptyClaim(batchJob);
        }

        // 5. 선점한 row는 lock이 해제된 뒤 target 단위로 Redis 조회와 MySQL 반영을 수행한다.
        for (LineDailyBatchTarget target : claimedTargets) {
            processTarget(batchJob, target, workerId);
        }

        // 6. 이번 cycle에서 처리한 row가 있으므로 다음 cycle을 즉시 이어가도록 scheduler에 알린다.
        return LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY;
    }

    /**
     * 선점 가능한 target row가 없을 때의 후속 처리를 결정한다.
     * non-terminal row가 남아 있으면 대기 재시도를 요청하고, 남아 있지 않으면 batch 완료 CAS를 시도한다.
     */
    private LineDailyUsageSyncWorkerRunResult handleEmptyClaim(LineDailyBatchJob batchJob) {
        // 1. 같은 usage_date에 아직 terminal 상태가 아닌 target row가 남았는지 일반 SELECT로 확인한다.
        long nonTerminalCount = lineDailyBatchTargetMapper.countNonTerminalByUsageDate(batchJob.getUsageDate());
        // 2. 남은 row가 있으면 다른 worker 처리 또는 lease timeout을 기다리도록 1분 재확인을 요청한다.
        if (nonTerminalCount > 0) {
            log.info(
                    "line_daily_usage_sync_worker_empty_poll_wait batchJobId={} usageDate={} nonTerminalCount={}",
                    batchJob.getId(),
                    batchJob.getUsageDate(),
                    nonTerminalCount
            );
            return LineDailyUsageSyncWorkerRunResult.WAIT_FOR_EMPTY_POLL;
        }

        // 3. non-terminal row가 없으면 metadata count 합계 조건으로 usage sync batch 완료 CAS를 시도한다.
        boolean completed = lineDailyBatchJobService.completeRunningUsageSyncBatchIfCountsMatch(batchJob);
        // 4. 동시 worker 중 CAS 성공 여부를 로그로 남기고 현재 worker loop는 종료한다.
        log.info(
                "line_daily_usage_sync_worker_completion_cas batchJobId={} usageDate={} completed={}",
                batchJob.getId(),
                batchJob.getUsageDate(),
                completed
        );
        return LineDailyUsageSyncWorkerRunResult.STOP;
    }

    /**
     * target row 하나를 Redis 조회부터 MySQL 반영까지 처리하고 실패 시 retry 정책에 맞게 상태를 기록한다.
     */
    private void processTarget(LineDailyBatchJob batchJob, LineDailyBatchTarget target, String workerId) {
        try {
            // 1. Redis에서 target line의 일별 총량, 앱별 사용량, 공유풀 사용량 snapshot을 조회한다.
            LineDailyUsageReadResult snapshot = lineDailyUsageRedisReader.read(target);
            // 2. 조회 결과가 실제 DB insert 대상인지, 전체 key 없음으로 SKIPPED 대상인지 기록한다.
            log.info(
                    "line_daily_usage_sync_worker_read_usage targetId={} usageDate={} lineId={} hasAnyUsage={}",
                    target.getId(),
                    target.getUsageDate(),
                    target.getLineId(),
                    snapshot.hasAnyUsage()
            );
            // 3. MySQL 트랜잭션 안에서 usage insert, target terminal 전환, metadata count 증가를 수행한다.
            lineDailyUsageSyncPersistenceService.persistUsageAndCompleteTarget(
                    batchJob.getId(),
                    target,
                    snapshot,
                    workerId
            );
        } catch (RuntimeException e) {
            // 4. Redis 조회 또는 DB 반영 중 실패하면 retry 정책에 맞는 target 상태로 기록한다.
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
        // 1. DB에 저장 가능한 길이와 로그 가독성을 위해 예외 타입과 메시지를 한 문자열로 요약한다.
        String errorMessage = summarizeException(exception);
        // 2. Redis/DB 인프라성 오류는 RETRYABLE 또는 재시도 한도 초과 FAILED 경로로 기록한다.
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

        // 3. 불완전 Redis hash 같은 자동 복구 불가 오류는 즉시 FAILED terminal 상태로 기록한다.
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

    /**
     * worker 처리 실패가 재시도 가능한지 판단한다.
     * Redis runtime 분류와 DB lock/timeout 계열 예외 분류를 합쳐 target 상태 결정에 사용한다.
     */
    private boolean isRetryableFailure(RuntimeException exception) {
        // 1. Redis runtime 계층이 재시도 가능 인프라 장애로 분류한 예외인지 먼저 확인한다.
        // 2. Redis 예외가 아니면 Spring DataAccessException 중 lock/timeout 계열인지 확인한다.
        return trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)
                || isRetryableDbException(exception);
    }

    /**
     * Spring DataAccessException의 cause chain에서 재시도 가능한 DB 인프라 예외를 찾는다.
     * query timeout, lock 획득 실패, deadlock, pessimistic lock 실패, 동시성 실패만 재시도 대상으로 인정한다.
     */
    private boolean isRetryableDbException(DataAccessException exception) {
        // 1. Spring 예외 wrapper 안쪽 cause까지 순회해 실제 lock/timeout 원인을 찾는다.
        Throwable current = exception;
        while (current != null) {
            // 2. 일시적 timeout, deadlock, pessimistic lock 실패, 동시성 실패만 재시도 대상으로 본다.
            if (current instanceof QueryTimeoutException
                    || current instanceof CannotAcquireLockException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof ConcurrencyFailureException) {
                return true;
            }
            // 3. 현재 계층에서 찾지 못하면 다음 cause로 내려간다.
            current = current.getCause();
        }
        // 4. 끝까지 재시도 가능 DB 예외를 찾지 못하면 non-retryable로 둔다.
        return false;
    }

    /**
     * RuntimeException이 Spring DataAccessException인지 확인하고 DB 재시도 분류로 위임한다.
     * DB 계열 예외가 아니면 non-retryable 판단을 유지한다.
     */
    private boolean isRetryableDbException(RuntimeException exception) {
        // 1. Spring DataAccessException 계열만 DB 재시도 분류 대상으로 위임한다.
        return exception instanceof DataAccessException dataAccessException
                && isRetryableDbException(dataAccessException);
    }

    /**
     * target row 오류 컬럼과 로그에 남길 예외 요약 문자열을 만든다.
     * 메시지가 없을 때도 예외 타입명은 남겨 원인 추적 단서를 보존한다.
     */
    private String summarizeException(RuntimeException exception) {
        // 1. 예외 메시지가 없으면 예외 타입명만 저장해 last_error_message가 비지 않게 한다.
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        // 2. 메시지가 있으면 타입명과 원문 메시지를 함께 남겨 운영자가 원인을 좁힐 수 있게 한다.
        return exception.getClass().getSimpleName() + ": " + message;
    }

    /**
     * PROCESSING target row의 소유자를 식별할 worker id를 생성한다.
     * usage_date와 UUID를 함께 포함해 날짜별 worker 실행과 서버 간 동시 처리를 구분한다.
     */
    private String buildWorkerId(LocalDate usageDate) {
        // 1. usage_date와 UUID를 함께 사용해 여러 서버 worker의 PROCESSING 소유자를 구분한다.
        return "line-daily-worker:" + usageDate + ":" + UUID.randomUUID();
    }
}
