package com.pooli.traffic.service.decision;

import java.time.Duration;
import java.time.Instant;
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
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
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
                && currentResult.getStatus() != TrafficLuaStatus.HYDRATE)) {
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

        // 잔량/QoS hash 누락이면 Redis hydrate 후 같은 통합 Lua를 재시도합니다. Refill은 수행하지 않습니다.
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
     * 개인/공유 잔량 또는 QoS hash 누락 상태면 hydrate 후 동일 통합 Lua 차감을 재시도합니다.
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
        // HYDRATE가 아닌 상태는 잔량/QoS 복구 대상이 아닙니다.
        if (currentResult.getStatus() != TrafficLuaStatus.HYDRATE) {
            return currentResult;
        }

        TrafficLuaDeductExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            // 통합 Lua가 참조하는 개인 잔량, 공유 잔량, 개인 QoS 필드를 함께 준비합니다.
            applyUnifiedHydrate(payload, targetMonth, individualBalanceKey, sharedBalanceKey);
            retriedResult = trafficDeductLuaExecutor.executeUnifiedWithRetry(
                    payload,
                    requestedDataBytes,
                    context,
                    TrafficFailureStage.HYDRATE
            );
            if (retriedResult.getStatus() != TrafficLuaStatus.HYDRATE) {
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
     * 통합 Lua가 참조하는 개인풀 잔량, 공유풀 잔량, 개인 QoS 필드를 함께 준비합니다.
     */
    private void applyUnifiedHydrate(
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String individualBalanceKey,
            String sharedBalanceKey
    ) {
        // 개인풀 hydrate는 amount와 QoS 필드를 준비합니다.
        applyHydrateWithOptionalLock(
                TrafficPoolType.INDIVIDUAL,
                payload,
                targetMonth,
                individualBalanceKey,
                resolveHydrateLockKey(TrafficPoolType.INDIVIDUAL, payload)
        );
        // 공유풀 hydrate는 amount 필드만 준비합니다.
        applyHydrateWithOptionalLock(
                TrafficPoolType.SHARED,
                payload,
                targetMonth,
                sharedBalanceKey,
                resolveHydrateLockKey(TrafficPoolType.SHARED, payload)
        );
    }

    /**
     * hydrate 중복 실행을 줄이기 위해 설정에 따라 Redis lock을 획득한 worker만 실제 hydrate를 수행합니다.
     */
    private void applyHydrateWithOptionalLock(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            String hydrateLockKey
    ) {
        // 로컬/테스트 설정에서는 중복 hydrate를 허용해 즉시 적재합니다.
        if (!hydrateLockEnabled) {
            applyHydrate(poolType, payload, targetMonth, balanceKey);
            return;
        }
        // 운영성 경합 방지를 위해 같은 owner hydrate는 Redis lock 보유자만 수행합니다.
        if (!tryAcquireHydrateLock(hydrateLockKey, payload.getTraceId())) {
            return;
        }
        try {
            applyHydrate(poolType, payload, targetMonth, balanceKey);
        } finally {
            trafficLuaScriptInfraService.executeLockRelease(hydrateLockKey, payload.getTraceId());
        }
    }

    /**
     * 월별 잔량 hash를 생성하고, 개인풀인 경우 QoS 한도를 같은 hash에 적재합니다.
     */
    private void applyHydrate(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey
    ) {
        // 월별 잔량 hash는 DB 원천값과 월말+10일 TTL로 생성합니다. amount=-1은 무제한으로 그대로 유지합니다.
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);
        Long sourceAmount = switch (poolType) {
            case INDIVIDUAL -> trafficRefillSourceMapper.selectIndividualRemaining(payload.getLineId());
            case SHARED -> trafficRefillSourceMapper.selectSharedRemaining(payload.getFamilyId());
        };
        trafficRemainingBalanceCacheService.hydrateBalance(
                balanceKey,
                sourceAmount == null ? 0L : sourceAmount,
                monthlyExpireAt
        );
        if (poolType == TrafficPoolType.INDIVIDUAL) {
            // QoS 한도는 LINE -> PLAN 조인 결과를 Redis 저장 단위로 변환해 개인풀 hash에 저장합니다.
            long qosSpeedLimit = loadIndividualQosSpeedLimit(payload);
            trafficRemainingBalanceCacheService.putQos(balanceKey, qosSpeedLimit);
        }
        trafficHydrateMetrics.incrementHydrate(poolType);
    }

    /**
     * LINE -> PLAN 조인 결과의 qos_speed_limit 값을 Redis 저장 단위로 변환합니다.
     */
    private long loadIndividualQosSpeedLimit(TrafficPayloadReqDto payload) {
        if (payload == null || payload.getLineId() == null) {
            return 0L;
        }

        Long rawQosSpeedLimit = trafficRefillSourceMapper.selectIndividualQosSpeedLimit(payload.getLineId());
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

}
