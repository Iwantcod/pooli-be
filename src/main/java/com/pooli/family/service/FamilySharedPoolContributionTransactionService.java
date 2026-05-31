package com.pooli.family.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.monitoring.metrics.TrafficSharedPoolContributionCleanupMetrics;
import com.pooli.traffic.domain.TrafficSharedPoolContributionLuaResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockPair;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유풀 기여 outbox 생성과 Redis-first 처리 본문에 AOP 트랜잭션 경계를 제공합니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class FamilySharedPoolContributionTransactionService {

    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final FamilySharedPoolMapper sharedPoolMapper;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final TrafficSharedPoolContributionCleanupMetrics cleanupMetrics;

    /**
     * 공유풀 기여 요청의 복구 기준점이 될 outbox 레코드를 별도 트랜잭션에서 생성합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxCreateResult createOutbox(
            SharedPoolContributionOutboxPayload payload,
            String traceId
    ) {
        return redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(payload, traceId);
    }

    /**
     * 공유풀 기여의 Redis-first 처리 본문을 별도 트랜잭션에서 수행합니다.
     *
     * <p>1. payload와 traceId를 검증합니다.
     * <br>2. Redis balance key, metadata key, hydrate lock key를 계산합니다.
     * <br>3. 개인/공유 hydrate lock을 모두 획득합니다.
     * <br>4. 요청 경로는 apply Lua, 복구 경로는 recover Lua를 실행합니다.
     * <br>5. Lua 결과에 따라 MySQL 반영, outbox 상태 전이, after-commit cleanup 등록을 수행합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingResult processContribution(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId,
            boolean recovery
    ) {
        // [1] 깨진 payload는 Redis/MySQL 어느 쪽도 건드리지 않고 즉시 실패시킵니다.
        validatePayload(payload, traceId);

        // [2] 같은 trace/month/owner에 대해 Lua와 lock이 사용할 Redis key를 확정합니다.
        YearMonth targetMonth = YearMonth.parse(payload.getTargetMonth(), YEAR_MONTH_FORMATTER);
        String metadataKey = trafficRedisKeyFactory.sharedPoolContributionMetadataKey(traceId);
        String individualBalanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
        String sharedBalanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        String individualLockKey = trafficRedisKeyFactory.indivHydrateLockKey(payload.getLineId());
        String sharedLockKey = trafficRedisKeyFactory.sharedHydrateLockKey(payload.getFamilyId());

        // [3] 두 lock을 모두 잡지 못하면 지금은 처리하지 않고 즉시 재시도 또는 scheduler 복구 대상으로 남깁니다.
        Optional<HydrateLockPair> lockPair =
                trafficLuaScriptInfraService.tryAcquireHydrateLocks(individualLockKey, sharedLockKey);
        if (lockPair.isEmpty()) {
            return ProcessingResult.RETRYABLE_FAILURE;
        }

        HydrateLockPair acquiredLocks = lockPair.get();
        try {
            // [4] 요청과 복구는 같은 트랜잭션 본문을 쓰되 Redis Lua script만 분기합니다.
            TrafficSharedPoolContributionLuaResult luaResult = recovery
                    ? trafficLuaScriptInfraService.executeSharedPoolContributionRecover(
                            metadataKey,
                            individualBalanceKey,
                            sharedBalanceKey,
                            Boolean.TRUE.equals(payload.getIndividualUnlimited())
                    )
                    : trafficLuaScriptInfraService.executeSharedPoolContributionApply(
                            metadataKey,
                            individualBalanceKey,
                            sharedBalanceKey,
                            traceId,
                            payload.getAmount(),
                            Boolean.TRUE.equals(payload.getIndividualUnlimited())
                    );

            // [5] Lua status를 도메인 처리 결과와 outbox 상태 전이로 변환합니다.
            return handleLuaResult(outboxId, payload, traceId, metadataKey, acquiredLocks, luaResult, recovery);
        } catch (RuntimeException e) {
            // [6] transaction commit 이전 실패에서는 metadata를 보존하고 현재 worker lock만 반납합니다.
            releaseLocksAfterFailure(acquiredLocks, e);
            throw e;
        }
    }

    /**
     * Lua status를 MySQL 반영, outbox 상태 전이, cleanup 정책으로 해석합니다.
     */
    private ProcessingResult handleLuaResult(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId,
            String metadataKey,
            HydrateLockPair acquiredLocks,
            TrafficSharedPoolContributionLuaResult luaResult,
            boolean recovery
    ) {
        String status = luaResult.getStatus();
        if ("APPLIED".equals(status)) {
            // [1] Redis 적용이 확정된 뒤에만 hydrate source와 일자별 기여 집계를 반영합니다.
            applyMysqlContribution(payload);
            // [2] MySQL 반영까지 끝난 요청만 SUCCESS로 닫습니다.
            redisOutboxRecordService.markSuccess(outboxId);
            // [3] metadata 삭제와 lock 해제는 DB commit 성공 이후로 미룹니다.
            registerAfterCommitCleanup(outboxId, payload, traceId, metadataKey, acquiredLocks, recovery);
            return ProcessingResult.SUCCESS;
        }

        if ("METADATA_MISSING".equals(status)
                || "INSUFFICIENT_INDIVIDUAL".equals(status)
                || "INVALID_ARGUMENT".equals(status)) {
            // [4] 확정 실패 상태는 반복 재시도하지 않도록 CANCELED로 닫고 commit 후 정리합니다.
            redisOutboxRecordService.markCanceled(outboxId);
            registerAfterCommitCleanup(outboxId, payload, traceId, metadataKey, acquiredLocks, recovery);
            return ProcessingResult.CANCELED;
        }

        // [5] conflict 등 재시도 가능한 상태는 metadata를 보존하고 lock만 반납합니다.
        trafficLuaScriptInfraService.releaseHydrateLocks(acquiredLocks);
        return ProcessingResult.RETRYABLE_FAILURE;
    }

    /**
     * DB commit 성공 이후에만 Redis metadata cleanup을 실행하도록 transaction synchronization을 등록합니다.
     */
    private void registerAfterCommitCleanup(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId,
            String metadataKey,
            HydrateLockPair acquiredLocks,
            boolean recovery
    ) {
        // DB commit이 확정된 뒤에만 metadata를 삭제해야 rollback 후 scheduler 복구 기준점이 사라지지 않습니다.
        Runnable cleanup = () -> executeCleanup(outboxId, payload, traceId, metadataKey, acquiredLocks, recovery);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        releaseLocksAfterRollback(acquiredLocks);
                    }
                }
            });
            return;
        }

        cleanup.run();
    }

    /**
     * 공유풀 기여 metadata 삭제와 hydrate lock 해제를 수행하고, 실패 시 별도 metric/log로만 기록합니다.
     */
    private void executeCleanup(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId,
            String metadataKey,
            HydrateLockPair acquiredLocks,
            boolean recovery
    ) {
        try {
            // [1] cleanup Lua는 metadata 삭제와 owner token 기반 lock 해제를 한 번에 처리합니다.
            trafficLuaScriptInfraService.executeSharedPoolContributionCleanup(metadataKey, acquiredLocks);
        } catch (RuntimeException e) {
            // [2] cleanup 실패는 이미 commit된 비즈니스 결과를 되돌리지 않고 residue 관측 이벤트로 남깁니다.
            String path = recovery ? "recovery" : "request";
            String reason = resolveFailureReason(e);
            cleanupMetrics.incrementFailure(path, reason);
            log.warn(
                    "shared_pool_contribution_cleanup_failed outboxId={} traceId={} familyId={} lineId={} metadataKey={} recovery={} reason={}",
                    outboxId,
                    traceId,
                    payload.getFamilyId(),
                    payload.getLineId(),
                    metadataKey,
                    recovery,
                    reason,
                    e
            );
        }
    }

    /**
     * cleanup 실패 원인을 Prometheus tag로 사용할 수 있는 고정 문자열로 분류합니다.
     */
    private String resolveFailureReason(RuntimeException e) {
        if (trafficRedisFailureClassifier.isTimeoutFailure(e)) {
            return "timeout";
        }
        if (trafficRedisFailureClassifier.isConnectionFailure(e)) {
            return "connection";
        }
        if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(e)) {
            return "non_retryable";
        }
        return "unknown";
    }

    /**
     * Redis 적용이 확정된 공유풀 기여를 RDB hydrate source와 일자별 집계에 반영합니다.
     */
    private void applyMysqlContribution(SharedPoolContributionOutboxPayload payload) {
        // [1] 요청 시점의 usageDate를 사용해 scheduler 복구도 같은 일자 집계에 반영되게 합니다.
        LocalDate usageDate = LocalDate.parse(payload.getUsageDate());
        if (!Boolean.TRUE.equals(payload.getIndividualUnlimited())) {
            // [2] 유한 개인풀만 LINE.total_data source를 차감합니다. 무제한 sentinel은 차감하지 않습니다.
            int updatedLineRows = sharedPoolMapper.updateLineRemainingData(payload.getLineId(), payload.getAmount());
            if (updatedLineRows == 0) {
                throw new ApplicationException(CommonErrorCode.DATABASE_ERROR, "개인풀 기여 source 갱신에 실패했습니다.");
            }
        }

        // [3] 공유풀 hydrate source 총량을 증가시킵니다.
        int updatedFamilyRows = sharedPoolMapper.updateFamilyPoolData(payload.getFamilyId(), payload.getAmount());
        if (updatedFamilyRows == 0) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR, "공유풀 source 갱신에 실패했습니다.");
        }
        // [4] 같은 날짜/가족/회선 기여량은 mapper의 upsert 계약으로 중복 증가를 방지합니다.
        sharedPoolMapper.insertContribution(
                payload.getFamilyId(),
                payload.getLineId(),
                payload.getAmount(),
                usageDate
        );
    }

    /**
     * Redis key 계산과 MySQL source 반영에 필요한 최소 payload 필드를 검증합니다.
     */
    private void validatePayload(SharedPoolContributionOutboxPayload payload, String traceId) {
        if (payload == null
                || payload.getLineId() == null || payload.getLineId() <= 0
                || payload.getFamilyId() == null || payload.getFamilyId() <= 0
                || payload.getAmount() == null || payload.getAmount() <= 0
                || payload.getTargetMonth() == null || payload.getTargetMonth().isBlank()
                || payload.getUsageDate() == null || payload.getUsageDate().isBlank()
                || traceId == null || traceId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "공유풀 기여 payload가 유효하지 않습니다.");
        }
    }

    /**
     * 예외 경로에서 현재 worker가 획득한 hydrate lock을 반납합니다.
     */
    private void releaseLocksAfterFailure(HydrateLockPair acquiredLocks, RuntimeException originalFailure) {
        try {
            // [1] metadata는 복구 기준점으로 남기고 owner token이 일치하는 lock만 해제합니다.
            trafficLuaScriptInfraService.releaseHydrateLocks(acquiredLocks);
        } catch (RuntimeException releaseFailure) {
            // [2] addSuppressed는 상위로 던질 originalFailure를 바꾸지 않고, lock 반납 실패를 함께 추적하게 합니다.
            originalFailure.addSuppressed(releaseFailure);
        }
    }

    /**
     * transaction rollback 시 metadata cleanup 없이 hydrate lock만 반납합니다.
     */
    private void releaseLocksAfterRollback(HydrateLockPair acquiredLocks) {
        try {
            // [1] rollback 후 scheduler 복구가 metadata를 사용할 수 있도록 cleanup Lua는 호출하지 않습니다.
            trafficLuaScriptInfraService.releaseHydrateLocks(acquiredLocks);
        } catch (RuntimeException e) {
            // [2] rollback 후 lock 반납 실패는 원 transaction 결과를 바꾸지 않고 로그만 남깁니다.
            log.warn("shared_pool_contribution_rollback_lock_release_failed", e);
        }
    }

    public enum ProcessingResult {
        SUCCESS,
        RETRYABLE_FAILURE,
        CANCELED
    }
}
