package com.pooli.traffic.service.decision;

import java.time.Instant;
import java.time.YearMonth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateService;
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

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long redisRetryBackoffMs;

    private final TrafficDeductLuaExecutor trafficDeductLuaExecutor;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficPolicyBootstrapService trafficPolicyBootstrapService;
    private final TrafficHydrateMetrics trafficHydrateMetrics;
    private final TrafficBalanceSnapshotHydrateService trafficBalanceSnapshotHydrateService;

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

        // 잔량/QoS snapshot 미준비이면 Redis hydrate 후 같은 통합 Lua를 재시도합니다.
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
                    targetMonth
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
            YearMonth targetMonth
    ) {
        if (hydrateStatus == TrafficLuaStatus.HYDRATE || hydrateStatus == TrafficLuaStatus.HYDRATE_INDIVIDUAL) {
            TrafficLuaDeductExecutionResult invalidResult = applyHydrate(
                    TrafficPoolType.INDIVIDUAL,
                    payload,
                    targetMonth
            );
            if (invalidResult != null) {
                return invalidResult;
            }
        }
        if (hydrateStatus == TrafficLuaStatus.HYDRATE || hydrateStatus == TrafficLuaStatus.HYDRATE_SHARED) {
            TrafficLuaDeductExecutionResult invalidResult = applyHydrate(
                    TrafficPoolType.SHARED,
                    payload,
                    targetMonth
            );
            if (invalidResult != null) {
                return invalidResult;
            }
        }
        return null;
    }

    /**
     * 공용 snapshot hydrate 서비스를 호출하고 stream 재시도에 필요한 결과만 해석합니다.
     */
    private TrafficLuaDeductExecutionResult applyHydrate(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth
    ) {
        TrafficBalanceSnapshotHydrateResult hydrateResult = switch (poolType) {
            case INDIVIDUAL -> trafficBalanceSnapshotHydrateService.hydrateIndividualSnapshot(
                    payload.getLineId(),
                    targetMonth
            );
            case SHARED -> trafficBalanceSnapshotHydrateService.hydrateSharedSnapshot(
                    payload.getFamilyId(),
                    targetMonth
            );
        };

        if (hydrateResult.isInvalidForStreamHydrate()) {
            trafficHydrateMetrics.incrementHydrate(poolType);
            trafficHydrateMetrics.incrementInvalidHydrate(poolType, hydrateResult.failureReason());
            return invalidHydrateResult(hydrateResult.failureReason());
        }
        if (!hydrateResult.isHydrated()) {
            return null;
        }
        trafficHydrateMetrics.incrementHydrate(poolType);
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
     * 풀 유형과 대상 월 기준으로 hydrate 대상 월별 잔량 hash key를 생성합니다.
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
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
}
