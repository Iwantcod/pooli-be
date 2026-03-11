package com.pooli.traffic.service;

import java.time.Instant;
import java.time.YearMonth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.domain.enums.TrafficRefillGateStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HYDRATE/REFILL 어댑터를 수행하는 서비스입니다.
 * 개인풀/공유풀 차감 Lua 결과를 보고 hydrate 1회 재시도, refill gate/lock 흐름을 처리합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficHydrateRefillAdapterService {

    private static final int HYDRATE_RETRY_MAX = 1;
    private static final int REFILL_RETRY_MAX = 1;

    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficQuotaSourcePort trafficQuotaSourcePort;
    private final TrafficQuotaCacheService trafficQuotaCacheService;

    public TrafficLuaExecutionResult executeIndividualWithRecovery(TrafficPayloadReqDto payload, long currentTickTargetData) {
        // 개인풀 분기 처리를 공통 메서드로 위임해 중복 코드를 줄인다.
        return executeWithRecovery(TrafficPoolType.INDIVIDUAL, payload, currentTickTargetData);
    }

    public TrafficLuaExecutionResult executeSharedWithRecovery(TrafficPayloadReqDto payload, long currentTickTargetData) {
        // 공유풀 분기 처리를 공통 메서드로 위임해 중복 코드를 줄인다.
        return executeWithRecovery(TrafficPoolType.SHARED, payload, currentTickTargetData);
    }

    private TrafficLuaExecutionResult executeWithRecovery(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            long currentTickTargetData
    ) {
        // 필수 값이 비어 있으면 이후 키/락 계산이 불가능하므로 ERROR로 즉시 종료한다.
        if (!isPayloadValidForPool(poolType, payload)) {
            return errorResult();
        }

        YearMonth targetMonth = resolveTargetMonth(payload);
        String balanceKey = resolveBalanceKey(poolType, payload, targetMonth);

        // 1차 Lua 차감 실행
        TrafficLuaExecutionResult initialResult = executeDeduct(poolType, balanceKey, currentTickTargetData);

        // HYDRATE 분기: 키 미존재 시 hydrate -> 동일 tick 1회 재시도
        TrafficLuaExecutionResult afterHydrateResult = handleHydrateIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                currentTickTargetData,
                initialResult
        );

        // NO_BALANCE 분기: refill gate/lock 성공 시 refill -> 동일 tick 1회 재시도
        return handleRefillIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                currentTickTargetData,
                afterHydrateResult
        );
    }

    private TrafficLuaExecutionResult handleHydrateIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long currentTickTargetData,
            TrafficLuaExecutionResult currentResult
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.HYDRATE) {
            return currentResult;
        }

        TrafficLuaExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            // DB hydrate 연동 전 단계이므로 source port가 제공하는 초기값으로 키를 복구한다.
            long initialAmount = trafficQuotaSourcePort.loadInitialAmount(poolType, payload, targetMonth);
            long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);
            trafficQuotaCacheService.hydrateBalance(balanceKey, initialAmount, monthlyExpireAt);

            retriedResult = executeDeduct(poolType, balanceKey, currentTickTargetData);
            if (retriedResult.getStatus() != TrafficLuaStatus.HYDRATE) {
                // HYDRATE에서 벗어나면 즉시 결과를 반환한다.
                return retriedResult;
            }
        }

        // 재시도 후에도 HYDRATE면 상위 오케스트레이터가 실패 분기로 처리할 수 있도록 그대로 반환한다.
        return retriedResult;
    }

    private TrafficLuaExecutionResult handleRefillIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long currentTickTargetData,
            TrafficLuaExecutionResult currentResult
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.NO_BALANCE) {
            return currentResult;
        }

        String lockKey = resolveLockKey(poolType, payload);

        TrafficLuaExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < REFILL_RETRY_MAX; retry++) {
            long currentAmount = trafficQuotaCacheService.readAmountOrDefault(balanceKey, 0L);
            long threshold = trafficQuotaSourcePort.resolveRefillThreshold(poolType, payload);

            TrafficRefillGateStatus gateStatus = trafficLuaScriptInfraService.executeRefillGate(
                    lockKey,
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    currentAmount,
                    threshold
            );

            if (gateStatus != TrafficRefillGateStatus.OK) {
                // WAIT/SKIP/FAIL이면 현재 tick에서 리필을 진행하지 않고 기존 결과를 유지한다.
                log.debug(
                        "traffic_refill_gate_not_ok traceId={} poolType={} gateStatus={}",
                        payload.getTraceId(),
                        poolType,
                        gateStatus
                );
                return retriedResult;
            }

            boolean lockOwned = trafficLuaScriptInfraService.executeLockHeartbeat(
                    lockKey,
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            );

            if (!lockOwned) {
                // lock 소유권이 없으면 동시성 충돌 가능성이 있어 리필을 건너뛴다.
                log.debug(
                        "traffic_refill_lock_not_owned traceId={} poolType={} lockKey={}",
                        payload.getTraceId(),
                        poolType,
                        lockKey
                );
                return retriedResult;
            }

            try {
                long refillUnit = trafficQuotaSourcePort.resolveRefillUnit(poolType, payload);
                long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);

                // 리필 작업 동안 lock TTL이 만료되지 않도록 heartbeat를 한번 더 수행한다.
                trafficLuaScriptInfraService.executeLockHeartbeat(
                        lockKey,
                        payload.getTraceId(),
                        TrafficRedisRuntimePolicy.LOCK_TTL_MS
                );
                trafficQuotaCacheService.refillBalance(balanceKey, refillUnit, monthlyExpireAt);

                // 리필 후 동일 tick 차감을 1회 재시도한다.
                retriedResult = executeDeduct(poolType, balanceKey, currentTickTargetData);
                return retriedResult;
            } finally {
                // 성공/실패와 무관하게 lock은 반드시 소유자 기준으로 해제한다.
                trafficLuaScriptInfraService.executeLockRelease(lockKey, payload.getTraceId());
            }
        }

        return retriedResult;
    }

    private TrafficLuaExecutionResult executeDeduct(
            TrafficPoolType poolType,
            String balanceKey,
            long currentTickTargetData
    ) {
        // 풀 유형에 맞는 Lua 스크립트를 선택해 차감 실행한다.
        return switch (poolType) {
            case INDIVIDUAL -> trafficLuaScriptInfraService.executeDeductIndivTick(balanceKey, currentTickTargetData);
            case SHARED -> trafficLuaScriptInfraService.executeDeductSharedTick(balanceKey, currentTickTargetData);
        };
    }

    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        // 풀 유형마다 잔량 키 구조가 다르므로 분기해 생성한다.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
    }

    private String resolveLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 리필 lock 키도 풀 유형마다 다르므로 분기해 생성한다.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivRefillLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedRefillLockKey(payload.getFamilyId());
        };
    }

    private YearMonth resolveTargetMonth(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            // enqueue 시각이 없으면 현재 시각(Asia/Seoul) 기준 월을 사용한다.
            return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        }

        // payload에 담긴 enqueue 시각을 기준으로 월 키(yyyymm)를 계산한다.
        return YearMonth.from(Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()));
    }

    private boolean isPayloadValidForPool(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (payload == null) {
            return false;
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            return false;
        }
        if (payload.getApiTotalData() == null || payload.getApiTotalData() < 0) {
            return false;
        }

        // 풀별 키 생성에 필요한 식별자가 없으면 처리할 수 없다.
        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() != null;
            case SHARED -> payload.getFamilyId() != null;
        };
    }

    private TrafficLuaExecutionResult errorResult() {
        return TrafficLuaExecutionResult.builder()
                .answer(-1L)
                .status(TrafficLuaStatus.ERROR)
                .build();
    }
}
