package com.pooli.traffic.service.decision;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 통합 Lua가 요청한 hydrate 복구만 수행합니다.
 *
 * <p>전역 정책 스냅샷 누락과 월별 잔량/QoS hash 누락을 복구하고,
 * 복구 후 재차감은 {@link TrafficDeductLuaExecutor}에 위임합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficHydrateService {

    private static final int HYDRATE_RETRY_MAX = 5;
    private static final long QOS_UPLOAD_MULTIPLIER = 125L;
    private static final String FAILURE_REASON_STALE_TARGET_MONTH = "STALE_TARGET_MONTH";
    private static final String FAILURE_REASON_SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND";

    @Value("${app.traffic.hydrate-lock.enabled:true}")
    private boolean hydrateLockEnabled;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long redisRetryBackoffMs;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficDeductLuaExecutor trafficDeductLuaExecutor;
    private final TrafficRefillSourceMapper trafficRefillSourceMapper;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;
    private final TrafficPolicyBootstrapService trafficPolicyBootstrapService;
    private final TrafficHydrateMetrics trafficHydrateMetrics;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    /**
     * Lua 상태가 hydrate 계열이면 필요한 데이터를 적재하고 같은 차감을 재시도합니다.
     */
    public TrafficLuaDeductExecutionResult recoverIfNeeded(
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            TrafficLuaDeductExecutionResult currentResult
    ) {
        if (currentResult == null
                || (currentResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE
                && !isBalanceHydrateStatus(currentResult.getStatus()))) {
            return currentResult;
        }
        if (!isPayloadValidForHydrate(payload)) {
            return currentResult;
        }

        YearMonth targetMonth = resolveTargetMonth(payload);
        String individualBalanceKey = resolveBalanceKey(TrafficPoolType.INDIVIDUAL, payload, targetMonth);
        String sharedBalanceKey = resolveBalanceKey(TrafficPoolType.SHARED, payload, targetMonth);

        // 전역 정책 스냅샷 누락이면 정책만 먼저 복구합니다.
        TrafficLuaDeductExecutionResult afterGlobalPolicyHydrateResult = handleGlobalPolicyHydrateIfNeeded(
                payload,
                individualBalanceKey,
                sharedBalanceKey,
                requestedDataBytes,
                context,
                currentResult
        );

        // 잔량/QoS snapshot 미준비이면 Redis hydrate 후 같은 통합 Lua를 재시도합니다. Refill은 수행하지 않습니다.
        return handleHydrateIfNeeded(
                payload,
                targetMonth,
                individualBalanceKey,
                sharedBalanceKey,
                requestedDataBytes,
                context,
                afterGlobalPolicyHydrateResult
        );
    }

    /**
     * 전역 정책 스냅샷 누락 상태면 정책을 재적재한 뒤 동일 통합 Lua 차감을 재시도합니다.
     */
    private TrafficLuaDeductExecutionResult handleGlobalPolicyHydrateIfNeeded(
            TrafficPayloadReqDto payload,
            String individualBalanceKey,
            String sharedBalanceKey,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            TrafficLuaDeductExecutionResult currentResult
    ) {
        // GLOBAL_POLICY_HYDRATE가 아닌 상태는 이 단계의 처리 대상이 아니므로 그대로 반환합니다.
        if (currentResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
            return currentResult;
        }

        TrafficLuaDeductExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            try {
                // Redis에 없는 전역 정책 활성화 스냅샷을 DB 기준으로 다시 적재합니다.
                trafficPolicyBootstrapService.hydrateOnDemand();
            } catch (ApplicationException | DataAccessException e) {
                log.error(
                        "traffic_hydrate_global_policy_failed poolType=UNIFIED traceId={} retry={}",
                        payload.getTraceId(),
                        retry + 1,
                        e
                );
            }

            // 복구 직후 동일 요청량으로 차감 Lua를 다시 실행해 상태 전이를 확인합니다.
            retriedResult = trafficDeductLuaExecutor.executeUnifiedWithRetry(
                    payload,
                    requestedDataBytes,
                    context,
                    TrafficFailureStage.HYDRATE
            );
            if (retriedResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
                return retriedResult;
            }
            // 다른 worker의 정책 hydrate 반영 지연을 고려해 짧게 대기합니다.
            sleepHydrateRetryBackoff(retry + 1);
        }

        log.error(
                "traffic_hydrate_global_policy_retry_exhausted poolType=UNIFIED traceId={} individualBalanceKey={} sharedBalanceKey={}",
                payload.getTraceId(),
                individualBalanceKey,
                sharedBalanceKey
        );
        return retriedResult;
    }

    /**
     * 개인/공유 잔량 snapshot 미준비 상태면 필요한 snapshot만 hydrate 후 동일 통합 Lua 차감을 재시도합니다.
     */
    private TrafficLuaDeductExecutionResult handleHydrateIfNeeded(
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String individualBalanceKey,
            String sharedBalanceKey,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            TrafficLuaDeductExecutionResult currentResult
    ) {
        // hydrate 계열이 아닌 상태는 잔량/QoS 복구 대상이 아닙니다.
        if (!isBalanceHydrateStatus(currentResult.getStatus())) {
            return currentResult;
        }

        TrafficLuaDeductExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            // Lua가 알려준 원인에 맞춰 필요한 snapshot만 준비합니다.
            TrafficLuaDeductExecutionResult invalidResult = applyUnifiedHydrate(
                    retriedResult.getStatus(),
                    payload,
                    targetMonth,
                    individualBalanceKey,
                    sharedBalanceKey
            );
            if (invalidResult != null) {
                return invalidResult;
            }
            retriedResult = trafficDeductLuaExecutor.executeUnifiedWithRetry(
                    payload,
                    requestedDataBytes,
                    context,
                    TrafficFailureStage.HYDRATE
            );
            if (!isBalanceHydrateStatus(retriedResult.getStatus())) {
                return retriedResult;
            }
            // hydrate lock 경합 또는 Redis 반영 지연을 흡수하기 위해 backoff 후 재시도합니다.
            sleepHydrateRetryBackoff(retry + 1);
        }

        log.error(
                "traffic_hydrate_retry_exhausted poolType=UNIFIED traceId={} individualBalanceKey={} sharedBalanceKey={}",
                payload.getTraceId(),
                individualBalanceKey,
                sharedBalanceKey
        );
        return retriedResult;
    }

    /**
     * 통합 Lua가 참조하는 월별 snapshot 중 요청된 범위만 준비합니다.
     */
    private TrafficLuaDeductExecutionResult applyUnifiedHydrate(
            TrafficLuaStatus hydrateStatus,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String individualBalanceKey,
            String sharedBalanceKey
    ) {
        if (hydrateStatus == TrafficLuaStatus.HYDRATE || hydrateStatus == TrafficLuaStatus.HYDRATE_INDIVIDUAL) {
            TrafficLuaDeductExecutionResult invalidResult = applyHydrateWithOptionalLock(
                    TrafficPoolType.INDIVIDUAL,
                    payload,
                    targetMonth,
                    individualBalanceKey,
                    resolveHydrateLockKey(TrafficPoolType.INDIVIDUAL, payload)
            );
            if (invalidResult != null) {
                return invalidResult;
            }
        }
        if (hydrateStatus == TrafficLuaStatus.HYDRATE || hydrateStatus == TrafficLuaStatus.HYDRATE_SHARED) {
            TrafficLuaDeductExecutionResult invalidResult = applyHydrateWithOptionalLock(
                    TrafficPoolType.SHARED,
                    payload,
                    targetMonth,
                    sharedBalanceKey,
                    resolveHydrateLockKey(TrafficPoolType.SHARED, payload)
            );
            if (invalidResult != null) {
                return invalidResult;
            }
        }
        return null;
    }

    /**
     * hydrate 중복 실행을 줄이기 위해 설정에 따라 Redis lock을 획득한 worker만 실제 hydrate를 수행합니다.
     */
    private TrafficLuaDeductExecutionResult applyHydrateWithOptionalLock(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            String hydrateLockKey
    ) {
        // 로컬/테스트 설정에서는 중복 hydrate를 허용해 즉시 적재합니다.
        if (!hydrateLockEnabled) {
            return applyHydrate(poolType, payload, targetMonth, balanceKey);
        }
        // 운영성 경합 방지를 위해 같은 owner hydrate는 Redis lock 보유자만 수행합니다.
        if (!tryAcquireHydrateLock(hydrateLockKey, payload.getTraceId())) {
            return null;
        }
        try {
            return applyHydrate(poolType, payload, targetMonth, balanceKey);
        } finally {
            trafficLuaScriptInfraService.executeLockRelease(hydrateLockKey, payload.getTraceId());
        }
    }

    /**
     * 월별 잔량 hash를 생성하고, 개인풀인 경우 QoS 한도를 같은 hash에 적재합니다.
     */
    private TrafficLuaDeductExecutionResult applyHydrate(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey
    ) {
        // 월별 잔량 hash는 DB 원천값과 월말+10일 TTL로 생성합니다. amount=-1은 무제한으로 그대로 유지합니다.
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);

        if (poolType == TrafficPoolType.INDIVIDUAL) {
            HydrateSnapshotDecision<TrafficIndividualBalanceSnapshot> decision =
                    resolveIndividualSnapshot(payload, targetMonth);
            if (decision.failureReason() != null) {
                trafficHydrateMetrics.incrementHydrate(poolType);
                trafficHydrateMetrics.incrementInvalidHydrate(poolType, decision.failureReason());
                return invalidHydrateResult(decision.failureReason());
            }
            TrafficIndividualBalanceSnapshot snapshot = decision.snapshot();
            if (snapshot == null) {
                return null;
            }
            trafficRemainingBalanceCacheService.hydrateIndividualSnapshot(
                    balanceKey,
                    snapshot.getAmount() == null ? 0L : snapshot.getAmount(),
                    resolveQosSpeedLimit(snapshot),
                    monthlyExpireAt
            );
        } else {
            HydrateSnapshotDecision<TrafficSharedBalanceSnapshot> decision =
                    resolveSharedSnapshot(payload, targetMonth);
            if (decision.failureReason() != null) {
                trafficHydrateMetrics.incrementHydrate(poolType);
                trafficHydrateMetrics.incrementInvalidHydrate(poolType, decision.failureReason());
                return invalidHydrateResult(decision.failureReason());
            }
            TrafficSharedBalanceSnapshot snapshot = decision.snapshot();
            if (snapshot == null) {
                return null;
            }
            trafficRemainingBalanceCacheService.hydrateSharedSnapshot(
                    balanceKey,
                    snapshot.getAmount() == null ? 0L : snapshot.getAmount(),
                    monthlyExpireAt
            );
        }
        trafficHydrateMetrics.incrementHydrate(poolType);
        return null;
    }

    private HydrateSnapshotDecision<TrafficIndividualBalanceSnapshot> resolveIndividualSnapshot(
            TrafficPayloadReqDto payload,
            YearMonth targetMonth
    ) {
        TrafficIndividualBalanceSnapshot snapshot =
                trafficRefillSourceMapper.selectIndividualBalanceSnapshot(payload.getLineId());
        HydrateSnapshotDecision<TrafficIndividualBalanceSnapshot> initialDecision =
                decideSnapshot(snapshot, targetMonth);
        if (initialDecision.failureReason() != null || initialDecision.snapshot() != null) {
            return initialDecision;
        }

        LocalDateTime targetMonthStart = targetMonth.atDay(1).atStartOfDay();
        trafficRefillSourceMapper.refreshIndividualBalanceIfBeforeTargetMonth(
                payload.getLineId(),
                targetMonthStart
        );
        return decideSnapshot(
                trafficRefillSourceMapper.selectIndividualBalanceSnapshot(payload.getLineId()),
                targetMonth
        );
    }

    private HydrateSnapshotDecision<TrafficSharedBalanceSnapshot> resolveSharedSnapshot(
            TrafficPayloadReqDto payload,
            YearMonth targetMonth
    ) {
        TrafficSharedBalanceSnapshot snapshot =
                trafficRefillSourceMapper.selectSharedBalanceSnapshot(payload.getFamilyId());
        HydrateSnapshotDecision<TrafficSharedBalanceSnapshot> initialDecision =
                decideSnapshot(snapshot, targetMonth);
        if (initialDecision.failureReason() != null || initialDecision.snapshot() != null) {
            return initialDecision;
        }

        LocalDateTime targetMonthStart = targetMonth.atDay(1).atStartOfDay();
        trafficRefillSourceMapper.refreshSharedBalanceIfBeforeTargetMonth(
                payload.getFamilyId(),
                targetMonthStart
        );
        return decideSnapshot(
                trafficRefillSourceMapper.selectSharedBalanceSnapshot(payload.getFamilyId()),
                targetMonth
        );
    }

    private <T> HydrateSnapshotDecision<T> decideSnapshot(T snapshot, YearMonth targetMonth) {
        LocalDateTime lastBalanceRefreshedAt = extractLastBalanceRefreshedAt(snapshot);
        if (snapshot == null || lastBalanceRefreshedAt == null) {
            return HydrateSnapshotDecision.failure(FAILURE_REASON_SNAPSHOT_NOT_FOUND);
        }

        YearMonth refreshedMonth = YearMonth.from(lastBalanceRefreshedAt);
        if (refreshedMonth.isAfter(targetMonth)) {
            return HydrateSnapshotDecision.failure(FAILURE_REASON_STALE_TARGET_MONTH);
        }
        if (refreshedMonth.isBefore(targetMonth)) {
            return HydrateSnapshotDecision.notReady();
        }
        return HydrateSnapshotDecision.ready(snapshot);
    }

    private LocalDateTime extractLastBalanceRefreshedAt(Object snapshot) {
        if (snapshot instanceof TrafficIndividualBalanceSnapshot individualSnapshot) {
            return individualSnapshot.getLastBalanceRefreshedAt();
        }
        if (snapshot instanceof TrafficSharedBalanceSnapshot sharedSnapshot) {
            return sharedSnapshot.getLastBalanceRefreshedAt();
        }
        return null;
    }

    private TrafficLuaDeductExecutionResult invalidHydrateResult(String failureReason) {
        return TrafficLuaDeductExecutionResult.builder()
                .indivDeducted(0L)
                .sharedDeducted(0L)
                .qosDeducted(0L)
                .status(TrafficLuaStatus.ERROR)
                .failureReason(failureReason)
                .build();
    }

    /**
     * LINE -> PLAN 조인 결과의 qos_speed_limit 값을 Redis 저장 단위로 변환합니다.
     */
    private long resolveQosSpeedLimit(TrafficIndividualBalanceSnapshot snapshot) {
        Long rawQosSpeedLimit = snapshot == null ? null : snapshot.getQosSpeedLimit();
        if (rawQosSpeedLimit == null || rawQosSpeedLimit < 0) {
            return 0L;
        }
        return rawQosSpeedLimit * QOS_UPLOAD_MULTIPLIER;
    }

    /**
     * 풀 유형과 대상 월 기준으로 hydrate 대상 월별 잔량 hash key를 생성합니다.
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
    }

    /**
     * 풀 유형별 hydrate lock key를 생성해 같은 owner의 중복 hydrate 경합을 제어합니다.
     */
    private String resolveHydrateLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivHydrateLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedHydrateLockKey(payload.getFamilyId());
        };
    }

    /**
     * traceId를 lock value로 사용해 짧은 TTL의 Redis hydrate lock 획득을 시도합니다.
     */
    private boolean tryAcquireHydrateLock(String lockKey, String traceId) {
        Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                traceId,
                Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * hydrate/retry 루프의 짧은 backoff를 적용하고 interrupt 발생 시 현재 thread 상태를 복구합니다.
     */
    private void sleepHydrateRetryBackoff(int retryAttempt) {
        long waitMs = TrafficRetryBackoffSupport.resolveDelayMs(redisRetryBackoffMs, retryAttempt);
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_hydrate_retry_sleep_interrupted retryAttempt={} delayMs={}",
                    retryAttempt,
                    waitMs
            );
        }
    }

    /**
     * 월별 잔량 key 기준 월을 enqueuedAt 기준으로 산출하고, 값이 없으면 현재 월을 사용합니다.
     */
    private YearMonth resolveTargetMonth(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        }
        return YearMonth.from(Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()));
    }

    /**
     * hydrate에 필요한 공통 payload 필드와 풀별 owner 식별자를 검증합니다.
     */
    private boolean isPayloadValidForHydrate(TrafficPayloadReqDto payload) {
        if (payload == null) {
            return false;
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            return false;
        }
        if (payload.getLineId() == null || payload.getLineId() <= 0) {
            return false;
        }
        return payload.getFamilyId() != null && payload.getFamilyId() > 0;
    }

    private boolean isBalanceHydrateStatus(TrafficLuaStatus status) {
        return status == TrafficLuaStatus.HYDRATE
                || status == TrafficLuaStatus.HYDRATE_INDIVIDUAL
                || status == TrafficLuaStatus.HYDRATE_SHARED;
    }

    private record HydrateSnapshotDecision<T>(T snapshot, String failureReason) {
        static <T> HydrateSnapshotDecision<T> ready(T snapshot) {
            return new HydrateSnapshotDecision<>(snapshot, null);
        }

        static <T> HydrateSnapshotDecision<T> notReady() {
            return new HydrateSnapshotDecision<>(null, null);
        }

        static <T> HydrateSnapshotDecision<T> failure(String failureReason) {
            return new HydrateSnapshotDecision<>(null, failureReason);
        }
    }

}
