package com.pooli.traffic.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficDbRefillClaimResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficRefillPlan;
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
    private static final long POLICY_REPEAT_BLOCK_ID = 1L;
    private static final long POLICY_IMMEDIATE_BLOCK_ID = 2L;
    private static final long POLICY_LINE_LIMIT_SHARED_ID = 3L;
    private static final long POLICY_LINE_LIMIT_DAILY_ID = 4L;
    private static final long POLICY_APP_DATA_ID = 5L;
    private static final long POLICY_APP_SPEED_ID = 6L;
    private static final long POLICY_APP_WHITELIST_ID = 7L;

    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficQuotaSourcePort trafficQuotaSourcePort;
    private final TrafficQuotaCacheService trafficQuotaCacheService;

    /**
     * 개인풀 차감 경로를 실행합니다.
     *
     * <p>호출자는 현재 tick 목표량을 전달하고, 이 메서드는 아래 흐름을 일괄 처리합니다.
     * 1) 개인풀 Lua 1차 차감
     * 2) 필요 시 HYDRATE 복구 후 재차감
     * 3) 필요 시 REFILL 게이트/락/DB 차감/Redis 충전 후 재차감
     *
     * @param payload 요청 단위 컨텍스트(traceId/lineId/familyId/appId 등)
     * @param currentTickTargetData 현재 tick에서 처리해야 할 목표 바이트
     * @return 개인풀 경로 최종 Lua 실행 결과(answer/status)
     */
    public TrafficLuaExecutionResult executeIndividualWithRecovery(TrafficPayloadReqDto payload, long currentTickTargetData) {
        // 개인풀 분기 처리를 공통 메서드로 위임해 중복 코드를 줄인다.
        return executeWithRecovery(TrafficPoolType.INDIVIDUAL, payload, currentTickTargetData);
    }

    /**
     * 공유풀 차감 경로를 실행합니다.
     *
     * <p>개인풀과 동일한 복구 규칙(HYDRATE/REFILL)을 적용하되,
     * 키/락/소유자 식별자는 공유풀(familyId) 기준으로 해석합니다.
     *
     * @param payload 요청 단위 컨텍스트
     * @param currentTickTargetData 현재 tick에서 공유풀에 요청할 목표 바이트
     * @return 공유풀 경로 최종 Lua 실행 결과(answer/status)
     */
    public TrafficLuaExecutionResult executeSharedWithRecovery(TrafficPayloadReqDto payload, long currentTickTargetData) {
        // 공유풀 분기 처리를 공통 메서드로 위임해 중복 코드를 줄인다.
        return executeWithRecovery(TrafficPoolType.SHARED, payload, currentTickTargetData);
    }

    /**
     * 풀 타입(개인/공유) 공통 복구 오케스트레이션을 수행합니다.
     *
     * <p>진행 순서:
     * 1) payload 유효성 검증(필수 식별자/traceId/apiTotalData)
     * 2) 대상 월/잔량 키 계산
     * 3) 1차 Lua 차감
     * 4) HYDRATE 필요 시 복구 + 같은 tick 재시도
     * 5) NO_BALANCE 시 REFILL 분기 처리
     *
     * @param poolType 처리 대상 풀 유형
     * @param payload 요청 컨텍스트
     * @param currentTickTargetData 현재 tick 목표 바이트
     * @return 복구 분기까지 반영된 최종 실행 결과
     */
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
        TrafficLuaExecutionResult initialResult = executeDeduct(poolType, payload, balanceKey, currentTickTargetData);

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

    /**
     * 현재 결과가 HYDRATE일 때만 DB 원천값으로 Redis 키를 복구하고 재시도합니다.
     *
     * <p>처리 규칙:
     * - status가 HYDRATE가 아니면 입력 결과를 그대로 반환
     * - HYDRATE면 DB 초기량으로 `hydrateBalance` 수행 후 같은 tick에서 Lua 1회 재호출
     * - 재시도 후에도 HYDRATE면 상위가 실패/후속 분기를 결정하도록 그대로 반환
     *
     * @param poolType 처리 대상 풀 유형
     * @param payload 요청 컨텍스트
     * @param targetMonth 월 기준 키 계산 값
     * @param balanceKey Redis 잔량 키
     * @param currentTickTargetData 현재 tick 목표 바이트
     * @param currentResult 1차 Lua 결과
     * @return hydrate 처리 후 결과(또는 원본 결과)
     */
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

            retriedResult = executeDeduct(poolType, payload, balanceKey, currentTickTargetData);
            if (retriedResult.getStatus() != TrafficLuaStatus.HYDRATE) {
                // HYDRATE에서 벗어나면 즉시 결과를 반환한다.
                return retriedResult;
            }
        }

        // 재시도 후에도 HYDRATE면 상위 오케스트레이터가 실패 분기로 처리할 수 있도록 그대로 반환한다.
        return retriedResult;
    }

    /**
     * 현재 결과가 NO_BALANCE일 때 REFILL 게이트/락/DB 차감/Redis 충전을 수행합니다.
     *
     * <p>핵심 흐름:
     * 1) NO_BALANCE가 아니면 그대로 반환
     * 2) 최신 버킷 기반 리필 계획(delta/unit/threshold) 계산
     * 3) refill_gate.lua로 리필 진입 가능 여부 확인(OK만 진행)
     * 4) lock heartbeat로 소유권 확인(소유자만 진행)
     * 5) DB에서 actualRefillAmount 확보(min(requested, dbRemaining))
     * 6) actualRefillAmount > 0 이면 Redis 충전 후 같은 tick 재차감 1회
     * 7) finally에서 lock 해제 보장
     *
     * @param poolType 처리 대상 풀 유형
     * @param payload 요청 컨텍스트
     * @param targetMonth 월 기준 키 계산 값
     * @param balanceKey Redis 잔량 키
     * @param currentTickTargetData 현재 tick 목표 바이트
     * @param currentResult HYDRATE 처리 이후 현재 결과
     * @return refill 처리 이후 결과(또는 원본 결과)
     */
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
            TrafficRefillPlan refillPlan = trafficQuotaSourcePort.resolveRefillPlan(poolType, payload);
            long delta = normalizeNonNegative(refillPlan == null ? null : refillPlan.getDelta());
            int bucketCount = normalizeNonNegativeInt(refillPlan == null ? null : refillPlan.getBucketCount());
            long requestedRefillUnit = normalizeNonNegative(refillPlan == null ? null : refillPlan.getRefillUnit());
            long threshold = Math.max(1L, normalizeNonNegative(refillPlan == null ? null : refillPlan.getThreshold()));
            String refillPlanSource = refillPlan == null || refillPlan.getSource() == null
                    ? "UNKNOWN"
                    : refillPlan.getSource();

            log.info(
                    "traffic_refill_plan_resolved traceId={} poolType={} balanceKey={} currentAmount={} delta={} bucketCount={} refillUnit={} threshold={} source={}",
                    payload.getTraceId(),
                    poolType,
                    balanceKey,
                    currentAmount,
                    delta,
                    bucketCount,
                    requestedRefillUnit,
                    threshold,
                    refillPlanSource
            );

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
                TrafficDbRefillClaimResult claimResult = trafficQuotaSourcePort.claimRefillAmountFromDb(
                        poolType,
                        payload,
                        targetMonth,
                        requestedRefillUnit
                );
                long dbRemainingBefore = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingBefore());
                long actualRefillAmount = normalizeNonNegative(claimResult == null ? null : claimResult.getActualRefillAmount());
                long dbRemainingAfter = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingAfter());
                if (actualRefillAmount <= 0) {
                    // DB에서 실제 차감된 양이 없으면 Redis 충전 없이 현재 결과를 유지한다.
                    log.debug(
                            "traffic_refill_db_noop traceId={} poolType={} requestedRefill={} threshold={} delta={} bucketCount={} source={} dbBefore={} actualRefill={} dbAfter={}",
                            payload.getTraceId(),
                            poolType,
                            requestedRefillUnit,
                            threshold,
                            delta,
                            bucketCount,
                            refillPlanSource,
                            dbRemainingBefore,
                            actualRefillAmount,
                            dbRemainingAfter
                    );
                    return retriedResult;
                }

                long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);

                // 리필 작업 동안 lock TTL이 만료되지 않도록 heartbeat를 한번 더 수행한다.
                trafficLuaScriptInfraService.executeLockHeartbeat(
                        lockKey,
                        payload.getTraceId(),
                        TrafficRedisRuntimePolicy.LOCK_TTL_MS
                );
                trafficQuotaCacheService.refillBalance(balanceKey, actualRefillAmount, monthlyExpireAt);
                log.info(
                        "traffic_refill_applied traceId={} poolType={} balanceKey={} requestedRefill={} threshold={} delta={} bucketCount={} source={} dbBefore={} actualRefill={} dbAfter={}",
                        payload.getTraceId(),
                        poolType,
                        balanceKey,
                        requestedRefillUnit,
                        threshold,
                        delta,
                        bucketCount,
                        refillPlanSource,
                        dbRemainingBefore,
                        actualRefillAmount,
                        dbRemainingAfter
                );

                // 리필 후 동일 tick 차감을 1회 재시도한다.
                retriedResult = executeDeduct(poolType, payload, balanceKey, currentTickTargetData);
                return retriedResult;
            } finally {
                // 성공/실패와 무관하게 lock은 반드시 소유자 기준으로 해제한다.
                trafficLuaScriptInfraService.executeLockRelease(lockKey, payload.getTraceId());
            }
        }

        return retriedResult;
    }

    /**
     * 풀 유형에 맞는 차감 Lua를 실행합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param balanceKey 대상 잔량 Redis 키
     * @param currentTickTargetData 현재 tick 목표 바이트
     * @return Lua 차감 결과(answer/status)
     */
    private TrafficLuaExecutionResult executeDeduct(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            String balanceKey,
            long currentTickTargetData
    ) {
        // 정책 게이트/사용량 키를 Lua에서 함께 처리할 수 있도록 현재 시각 기반 파생 키를 구성한다.
        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());
        LocalDate targetDate = now.toLocalDate();
        YearMonth targetUsageMonth = YearMonth.from(now);
        long nowEpochSecond = now.atZone(trafficRedisRuntimePolicy.zoneId()).toEpochSecond();
        int dayNum = now.getDayOfWeek().getValue() % 7;
        int secOfDay = now.toLocalTime().toSecondOfDay();
        long dailyExpireAt = trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(targetDate);
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetUsageMonth);

        String policyRepeatKey = trafficRedisKeyFactory.policyKey(POLICY_REPEAT_BLOCK_ID);
        String policyImmediateKey = trafficRedisKeyFactory.policyKey(POLICY_IMMEDIATE_BLOCK_ID);
        String policyLineLimitSharedKey = trafficRedisKeyFactory.policyKey(POLICY_LINE_LIMIT_SHARED_ID);
        String policyLineLimitDailyKey = trafficRedisKeyFactory.policyKey(POLICY_LINE_LIMIT_DAILY_ID);
        String policyAppDataKey = trafficRedisKeyFactory.policyKey(POLICY_APP_DATA_ID);
        String policyAppSpeedKey = trafficRedisKeyFactory.policyKey(POLICY_APP_SPEED_ID);
        String policyAppWhitelistKey = trafficRedisKeyFactory.policyKey(POLICY_APP_WHITELIST_ID);

        String appWhitelistKey = trafficRedisKeyFactory.appWhitelistKey(payload.getLineId());
        String immediatelyBlockEndKey = trafficRedisKeyFactory.immediatelyBlockEndKey(payload.getLineId());
        String repeatBlockKey = trafficRedisKeyFactory.repeatBlockKey(payload.getLineId());
        String dailyTotalLimitKey = trafficRedisKeyFactory.dailyTotalLimitKey(payload.getLineId());
        String dailyTotalUsageKey = trafficRedisKeyFactory.dailyTotalUsageKey(payload.getLineId(), targetDate);
        String appDataDailyLimitKey = trafficRedisKeyFactory.appDataDailyLimitKey(payload.getLineId());
        String dailyAppUsageKey = trafficRedisKeyFactory.dailyAppUsageKey(payload.getLineId(), targetDate);
        String appSpeedLimitKey = trafficRedisKeyFactory.appSpeedLimitKey(payload.getLineId());

        // 풀 유형에 맞는 Lua 스크립트를 선택해 차감 실행한다.
        return switch (poolType) {
            case INDIVIDUAL -> {
                String speedBucketKey = trafficRedisKeyFactory.speedBucketIndividualKey(payload.getLineId(), nowEpochSecond);
                List<String> keys = List.of(
                        balanceKey,
                        policyRepeatKey,
                        policyImmediateKey,
                        policyLineLimitDailyKey,
                        policyAppDataKey,
                        policyAppSpeedKey,
                        policyAppWhitelistKey,
                        appWhitelistKey,
                        immediatelyBlockEndKey,
                        repeatBlockKey,
                        dailyTotalLimitKey,
                        dailyTotalUsageKey,
                        appDataDailyLimitKey,
                        dailyAppUsageKey,
                        appSpeedLimitKey,
                        speedBucketKey
                );
                List<String> args = List.of(
                        String.valueOf(currentTickTargetData),
                        String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum),
                        String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond),
                        String.valueOf(dailyExpireAt)
                );
                yield trafficLuaScriptInfraService.executeDeductIndivTick(keys, args);
            }
            case SHARED -> {
                String monthlySharedLimitKey = trafficRedisKeyFactory.monthlySharedLimitKey(payload.getLineId());
                String monthlySharedUsageKey = trafficRedisKeyFactory.monthlySharedUsageKey(payload.getLineId(), targetUsageMonth);
                String speedBucketKey = trafficRedisKeyFactory.speedBucketSharedKey(payload.getFamilyId(), nowEpochSecond);
                List<String> keys = List.of(
                        balanceKey,
                        policyRepeatKey,
                        policyImmediateKey,
                        policyLineLimitSharedKey,
                        policyLineLimitDailyKey,
                        policyAppDataKey,
                        policyAppSpeedKey,
                        policyAppWhitelistKey,
                        appWhitelistKey,
                        immediatelyBlockEndKey,
                        repeatBlockKey,
                        dailyTotalLimitKey,
                        dailyTotalUsageKey,
                        monthlySharedLimitKey,
                        monthlySharedUsageKey,
                        appDataDailyLimitKey,
                        dailyAppUsageKey,
                        appSpeedLimitKey,
                        speedBucketKey
                );
                List<String> args = List.of(
                        String.valueOf(currentTickTargetData),
                        String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum),
                        String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond),
                        String.valueOf(dailyExpireAt),
                        String.valueOf(monthlyExpireAt)
                );
                yield trafficLuaScriptInfraService.executeDeductSharedTick(keys, args);
            }
        };
    }

    /**
     * 풀 유형과 월 기준으로 Redis 잔량 키를 생성합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param payload 요청 식별자(lineId/familyId) 포함 컨텍스트
     * @param targetMonth 키 suffix(yyyymm) 계산 기준 월
     * @return remaining_indiv/shared_amount 키
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        // 풀 유형마다 잔량 키 구조가 다르므로 분기해 생성한다.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
    }

    /**
     * 풀 유형 기준으로 refill lock 키를 생성합니다.
     *
     * @param poolType 개인/공유 풀 구분
     * @param payload 요청 식별자(lineId/familyId) 포함 컨텍스트
     * @return indiv/shared refill lock 키
     */
    private String resolveLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 리필 lock 키도 풀 유형마다 다르므로 분기해 생성한다.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivRefillLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedRefillLockKey(payload.getFamilyId());
        };
    }

    /**
     * 요청이 속한 기준 월(YearMonth)을 결정합니다.
     *
     * <p>정합성 규칙에 따라 가능하면 payload.enqueuedAt을 우선 사용하고,
     * 값이 없거나 비정상이면 런타임 현재 시각(Asia/Seoul) 기준으로 대체합니다.
     *
     * @param payload 요청 컨텍스트
     * @return DB/Redis 월 키 정합성에 사용할 기준 월
     */
    private YearMonth resolveTargetMonth(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            // enqueue 시각이 없으면 현재 시각(Asia/Seoul) 기준 월을 사용한다.
            return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        }

        // payload에 담긴 enqueue 시각을 기준으로 월 키(yyyymm)를 계산한다.
        return YearMonth.from(Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()));
    }

    /**
     * 풀 처리에 필요한 필수 payload 값이 모두 있는지 검증합니다.
     *
     * <p>공통 필수값: traceId, lineId, appId, apiTotalData(0 이상)<br>
     * 풀별 필수값: INDIVIDUAL=lineId, SHARED=familyId(+lineId 공통 필수)
     *
     * @param poolType 검증 대상 풀 유형
     * @param payload 검증할 요청 컨텍스트
     * @return 유효하면 true, 누락/비정상이 있으면 false
     */
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
        if (payload.getLineId() == null || payload.getLineId() <= 0) {
            return false;
        }
        if (payload.getAppId() == null || payload.getAppId() < 0) {
            return false;
        }

        // 풀별 키 생성에 필요한 식별자가 없으면 처리할 수 없다.
        return switch (poolType) {
            case INDIVIDUAL -> true;
            case SHARED -> payload.getFamilyId() != null && payload.getFamilyId() > 0;
        };
    }

    /**
     * 유효성 실패 등 즉시 종료 상황에서 사용하는 표준 ERROR 결과를 생성합니다.
     *
     * @return answer=-1, status=ERROR
     */
    private TrafficLuaExecutionResult errorResult() {
        return TrafficLuaExecutionResult.builder()
                .answer(-1L)
                .status(TrafficLuaStatus.ERROR)
                .build();
    }

    /**
     * Long 값을 음수/NULL 방어 규칙으로 0 이상 값으로 보정합니다.
     *
     * @param value 보정 대상 값
     * @return 0 이상 정규화 값
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * Integer 값을 음수/NULL 방어 규칙으로 0 이상 값으로 보정합니다.
     *
     * @param value 보정 대상 값
     * @return 0 이상 정규화 값
     */
    private int normalizeNonNegativeInt(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value;
    }
}
