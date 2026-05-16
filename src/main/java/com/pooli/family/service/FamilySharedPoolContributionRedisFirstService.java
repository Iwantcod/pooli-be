package com.pooli.family.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.traffic.domain.TrafficSharedPoolContributionLuaResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService.HydrateLockPair;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유풀 기여 요청과 outbox 복구를 같은 Redis-first 계약으로 처리합니다.
 *
 * <p>처리 순서는 outbox 선생성, Redis Lua 적용/복구, MySQL source 반영,
 * outbox 상태 전이, Redis metadata/lock cleanup입니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class FamilySharedPoolContributionRedisFirstService {

    private static final int IMMEDIATE_MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final FamilySharedPoolMapper sharedPoolMapper;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final PlatformTransactionManager transactionManager;

    /**
     * API 요청 경로에서 공유풀 기여를 접수하고 가능한 경우 즉시 처리합니다.
     *
     * <p>1. 현재 요청 traceId와 이번 달 payload를 만든다.
     * <br>2. outbox 레코드를 먼저 생성해 Redis 적용 이후 복구 기준점을 확보한다.
     * <br>3. 같은 traceId의 기존 기여 outbox가 있으면 중복 요청으로 보고 즉시 완료되지 않은 것으로 반환한다.
     * <br>4. 새 outbox가 생성되면 같은 payload로 즉시 처리 재시도를 수행한다.
     *
     * @return true면 Redis/MySQL 반영까지 즉시 완료되어 후속 Mongo 로그/알림을 진행해도 된다.
     */
    public boolean submit(Long lineId, Long familyId, Long amount, boolean individualUnlimited) {
        // [1] API 요청 단위 traceId와 현재월/기여일 payload를 확정합니다.
        String traceId = resolveTraceId();
        LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        YearMonth targetMonth = YearMonth.from(usageDate);
        SharedPoolContributionOutboxPayload payload = SharedPoolContributionOutboxPayload.builder()
                .lineId(lineId)
                .familyId(familyId)
                .amount(amount)
                .individualUnlimited(individualUnlimited)
                .targetMonth(trafficRedisRuntimePolicy.formatYyyyMm(targetMonth))
                .usageDate(usageDate.toString())
                .build();

        // [2] Redis 반영보다 먼저 outbox를 만들고, 이미 접수된 trace/event 조합은 중복으로 흡수합니다.
        OutboxCreateResult createResult = executeRequiresNew(() ->
                redisOutboxRecordService.createSharedPoolContributionProcessingIfAbsent(payload, traceId)
        );
        if (!createResult.created()) {
            return false;
        }

        // [3] 새 outbox가 생긴 요청만 Redis-first 처리 본문을 즉시 시도합니다.
        return runImmediateAttempts(createResult.outboxId(), payload, traceId);
    }

    /**
     * scheduler가 claim한 공유풀 기여 outbox를 복구 처리합니다.
     *
     * <p>1. outbox payload를 역직렬화한다.
     * <br>2. Redis metadata가 있으면 미반영분을 보정한 뒤 MySQL source를 반영한다.
     * <br>3. Redis metadata가 없으면 최초 Redis 적용이 없던 요청으로 보고 CANCELED 처리한다.
     * <br>4. scheduler 공통 계약에 맞춰 완료/취소는 SUCCESS, 재시도 대상은 FAIL로 반환한다.
     */
    public OutboxRetryResult recover(RedisOutboxRecord record) {
        // [1] scheduler 복구는 저장된 outbox payload만 신뢰합니다.
        SharedPoolContributionOutboxPayload payload =
                redisOutboxRecordService.readPayload(record, SharedPoolContributionOutboxPayload.class);
        // [2] 복구 경로도 요청 경로와 같은 transaction_2 본문을 사용하되 Lua recover script를 실행합니다.
        ProcessingResult result = executeRequiresNew(() ->
                processContribution(record.getId(), payload, record.getTraceId(), true)
        );
        return result == ProcessingResult.SUCCESS || result == ProcessingResult.CANCELED
                ? OutboxRetryResult.SUCCESS
                : OutboxRetryResult.FAIL;
    }

    /**
     * 요청 경로의 transaction_2 처리를 최대 3회 즉시 재시도합니다.
     *
     * <p>1. 성공 또는 취소는 즉시 종료한다.
     * <br>2. lock 경합 등 재시도 가능한 실패는 retry_count를 기록하고 다음 시도로 넘어간다.
     * <br>3. non-retryable 실패는 더 시도하지 않고 outbox 복구 대상으로 남긴다.
     * <br>4. 3회 모두 실패하면 사용자 요청은 실패로 확정하지 않고 scheduler 복구에 맡긴다.
     */
    private boolean runImmediateAttempts(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId
    ) {
        for (int attempt = 1; attempt <= IMMEDIATE_MAX_ATTEMPTS; attempt++) {
            try {
                // [1] 각 시도는 MySQL 상태 전이와 cleanup rollback 경계를 분리하기 위해 새 트랜잭션에서 수행합니다.
                ProcessingResult result = executeRequiresNew(() ->
                        processContribution(outboxId, payload, traceId, false)
                );
                if (result == ProcessingResult.SUCCESS) {
                    return true;
                }
                if (result == ProcessingResult.CANCELED) {
                    return false;
                }
                redisOutboxRecordService.markFailWithRetryCount(outboxId, attempt);
            } catch (RuntimeException e) {
                // [2] 실패 시도 수를 outbox에 남겨 scheduler가 동일 레코드를 이어서 판단할 수 있게 합니다.
                redisOutboxRecordService.markFailWithRetryCount(outboxId, attempt);
                if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(e)) {
                    log.warn(
                            "shared_pool_contribution_immediate_retry_stopped outboxId={} traceId={} attempt={}",
                            outboxId,
                            traceId,
                            attempt,
                            e
                    );
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 공유풀 기여의 Redis-first 본문을 한 번 수행합니다.
     *
     * <p>1. payload와 traceId를 검증한다.
     * <br>2. 월별 Redis balance key, trace metadata key, hydrate lock key를 계산한다.
     * <br>3. 개인/공유 hydrate lock을 모두 잡아 hydrate와 기여가 같은 owner snapshot을 동시에 쓰지 않게 한다.
     * <br>4. 요청 경로는 apply Lua, scheduler 경로는 recover Lua를 실행한다.
     * <br>5. Lua 결과를 MySQL 반영, outbox 상태 전이, cleanup으로 해석한다.
     */
    private ProcessingResult processContribution(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId,
            boolean recovery
    ) {
        // [1] payload가 깨졌으면 Redis/MySQL 어느 쪽도 건드리지 않고 실패시킵니다.
        validatePayload(payload, traceId);

        // [2] trace/month/owner 기준 Redis key를 한 곳에서 계산합니다.
        YearMonth targetMonth = YearMonth.parse(payload.getTargetMonth(), YEAR_MONTH_FORMATTER);
        String metadataKey = trafficRedisKeyFactory.sharedPoolContributionMetadataKey(traceId);
        String individualBalanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
        String sharedBalanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        String individualLockKey = trafficRedisKeyFactory.indivHydrateLockKey(payload.getLineId());
        String sharedLockKey = trafficRedisKeyFactory.sharedHydrateLockKey(payload.getFamilyId());

        // [3] 두 lock을 모두 잡지 못하면 지금은 처리하지 않고 즉시 재시도 또는 scheduler 대상에 남깁니다.
        Optional<HydrateLockPair> lockPair =
                trafficLuaScriptInfraService.tryAcquireHydrateLocks(individualLockKey, sharedLockKey);
        if (lockPair.isEmpty()) {
            return ProcessingResult.RETRYABLE_FAILURE;
        }

        HydrateLockPair acquiredLocks = lockPair.get();
        try {
            // [4] 요청 경로는 metadata 생성/재사용 apply script, 복구 경로는 metadata 기반 recover script를 사용합니다.
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

            // [5] Lua status를 도메인 처리 결과로 변환합니다.
            return handleLuaResult(outboxId, payload, metadataKey, acquiredLocks, luaResult);
        } catch (RuntimeException e) {
            // [6] Lua/MySQL/상태 전이 중 예외가 나면 현재 owner가 잡은 lock을 가능한 범위에서 반납합니다.
            releaseLocksAfterFailure(acquiredLocks, e);
            throw e;
        }
    }

    /**
     * Lua 결과 status를 MySQL 반영, outbox 상태, cleanup 정책으로 변환합니다.
     *
     * <p>APPLIED는 Redis 쪽 기여 상태가 확정된 상태이므로 MySQL source를 반영하고 SUCCESS 처리합니다.
     * METADATA_MISSING/INSUFFICIENT/INVALID는 더 진행할 수 없는 요청으로 보고 CANCELED 처리합니다.
     * 그 외 status는 metadata conflict 등 후속 재시도 판단이 필요한 상태로 남깁니다.
     */
    private ProcessingResult handleLuaResult(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String metadataKey,
            HydrateLockPair acquiredLocks,
            TrafficSharedPoolContributionLuaResult luaResult
    ) {
        String status = luaResult.getStatus();
        if ("APPLIED".equals(status)) {
            // [1] Redis 변경이 적용된 뒤에만 MySQL source와 일자별 기여 집계를 반영합니다.
            applyMysqlContribution(payload);
            // [2] MySQL 반영까지 끝난 요청만 SUCCESS로 닫습니다.
            redisOutboxRecordService.markSuccess(outboxId);
            // [3] 성공한 trace metadata와 현재 owner lock을 함께 정리합니다.
            trafficLuaScriptInfraService.executeSharedPoolContributionCleanup(metadataKey, acquiredLocks);
            return ProcessingResult.SUCCESS;
        }

        if ("METADATA_MISSING".equals(status)
                || "INSUFFICIENT_INDIVIDUAL".equals(status)
                || "INVALID_ARGUMENT".equals(status)) {
            // [4] metadata가 없거나 입력/잔량이 확정 실패면 반복 재시도하지 않도록 CANCELED로 닫습니다.
            redisOutboxRecordService.markCanceled(outboxId);
            trafficLuaScriptInfraService.executeSharedPoolContributionCleanup(metadataKey, acquiredLocks);
            return ProcessingResult.CANCELED;
        }

        // [5] 알 수 없거나 conflict 성격의 status는 metadata를 유지한 채 lock만 반납해 후속 재시도를 허용합니다.
        trafficLuaScriptInfraService.releaseHydrateLocks(acquiredLocks);
        return ProcessingResult.RETRYABLE_FAILURE;
    }

    /**
     * Redis 적용이 확정된 기여를 MySQL source에 반영합니다.
     *
     * <p>1. 유한 개인풀은 LINE.total_data를 차감한다.
     * <br>2. 가족 공유풀 총량을 증가시킨다.
     * <br>3. 같은 날짜/가족/회선의 기여 집계를 upsert한다.
     * <br>4. affected rows가 0이면 Redis와 MySQL 정합성을 맞출 수 없으므로 실패로 전파한다.
     */
    private void applyMysqlContribution(SharedPoolContributionOutboxPayload payload) {
        // [1] 요청 시점의 기여일을 사용해 scheduler 복구 시에도 같은 일자 집계에 반영합니다.
        LocalDate usageDate = LocalDate.parse(payload.getUsageDate());
        if (!Boolean.TRUE.equals(payload.getIndividualUnlimited())) {
            // [2] 유한 개인풀만 RDB hydrate source를 차감합니다. 무제한 sentinel은 차감하지 않습니다.
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
        // [4] 재시도 중 같은 날짜 집계가 중복 row로 분리되지 않도록 mapper의 upsert 계약을 사용합니다.
        sharedPoolMapper.insertContribution(
                payload.getFamilyId(),
                payload.getLineId(),
                payload.getAmount(),
                usageDate
        );
    }

    /**
     * Redis key 계산과 MySQL 반영에 필요한 최소 payload 필드를 검증합니다.
     */
    private void validatePayload(SharedPoolContributionOutboxPayload payload, String traceId) {
        // [1] 잘못된 outbox payload는 복구로 해결할 수 없으므로 내부 오류로 중단합니다.
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
            // [1] cleanup Lua까지 도달하지 못한 실패는 lock release Lua로 owner lock만 반납합니다.
            trafficLuaScriptInfraService.releaseHydrateLocks(acquiredLocks);
        } catch (RuntimeException releaseFailure) {
            // [2] 원래 실패 원인을 보존하면서 lock 반납 실패도 추적 가능하게 붙입니다.
            originalFailure.addSuppressed(releaseFailure);
        }
    }

    /**
     * 현재 요청 traceId를 확정합니다.
     *
     * <p>HTTP 요청은 LoggingFilter가 MDC에 넣은 traceId를 사용하고,
     * 테스트/비동기 호출처럼 MDC가 없으면 새 UUID를 생성합니다.
     */
    private String resolveTraceId() {
        // [1] 같은 요청 안의 로그/outbox/metadata가 같은 traceId를 공유하도록 MDC 값을 우선합니다.
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId.trim();
        }
        // [2] MDC가 없는 호출도 blank traceId로 outbox 생성에 실패하지 않도록 새 값을 만듭니다.
        return UUID.randomUUID().toString();
    }

    /**
     * 현재 호출부와 분리된 새 트랜잭션에서 callback을 실행합니다.
     *
     * <p>outbox 생성(transaction_1)과 Redis/MySQL 처리(transaction_2)를 분리하기 위한 local helper입니다.
     * outbox는 Redis 적용보다 먼저 commit되어야 Redis 성공 후 애플리케이션이 중단되어도 scheduler가
     * 복구 기준 레코드를 찾을 수 있습니다. 반대로 Redis Lua와 MySQL source 반영, outbox SUCCESS/CANCELED
     * 상태 전이는 하나의 transaction_2로 묶어 MySQL 반영과 outbox 종료 상태가 서로 어긋나지 않게 합니다.
     * Redis side effect는 DB rollback으로 되돌릴 수 없으므로, 실패 후 재시도는 commit된 outbox와
     * Redis metadata applied flag를 기준으로 중복 반영을 막습니다.
     */
    private <T> T executeRequiresNew(Supplier<T> callback) {
        // [1] self-invocation으로 @Transactional이 무시되는 문제를 피하려고 TransactionTemplate을 직접 사용합니다.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> callback.get());
    }

    private enum ProcessingResult {
        SUCCESS,
        RETRYABLE_FAILURE,
        CANCELED
    }

}
