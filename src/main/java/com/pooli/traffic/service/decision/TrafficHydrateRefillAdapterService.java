package com.pooli.traffic.service.decision;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import com.pooli.traffic.service.policy.TrafficLinePolicyHydrationService;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.monitoring.metrics.TrafficRefillMetrics;
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
 * HYDRATE/REFILL ?대뙌?곕? ?섑뻾?섎뒗 ?쒕퉬?ㅼ엯?덈떎.
 * 媛쒖씤?/怨듭쑀? 李④컧 Lua 寃곌낵瑜?蹂닿퀬 hydrate 1???ъ떆?? refill gate/lock ?먮쫫??泥섎━?⑸땲??
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficHydrateRefillAdapterService {

    private static final int HYDRATE_RETRY_MAX = 1;
    private static final int REFILL_RETRY_MAX = 1;
    private static final int DB_RETRY_MAX = 2;
    private static final long DB_RETRY_BACKOFF_MS = 50L;
    private static final long POLICY_REPEAT_BLOCK_ID = 1L;
    private static final long POLICY_IMMEDIATE_BLOCK_ID = 2L;
    private static final long POLICY_LINE_LIMIT_SHARED_ID = 3L;
    private static final long POLICY_LINE_LIMIT_DAILY_ID = 4L;
    private static final long POLICY_APP_DATA_ID = 5L;
    private static final long POLICY_APP_SPEED_ID = 6L;
    private static final long POLICY_APP_WHITELIST_ID = 7L;

    @Value("${app.traffic.hydrate-lock.enabled:true}")
    private boolean hydrateLockEnabled = true;

    @Value("${app.traffic.hydrate-lock.wait-ms:30}")
    private long hydrateLockWaitMs = 30L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficQuotaSourcePort trafficQuotaSourcePort;
    private final TrafficQuotaCacheService trafficQuotaCacheService;
    private final TrafficLinePolicyHydrationService trafficLinePolicyHydrationService;
    private final TrafficHydrateMetrics trafficHydrateMetrics;
    private final TrafficRefillMetrics trafficRefillMetrics;

    /**
     * 媛쒖씤? 李④컧 寃쎈줈瑜??ㅽ뻾?⑸땲??
     *
     * <p>?몄텧?먮뒗 ?꾩옱 event 紐⑺몴?됱쓣 ?꾨떖?섍퀬, ??硫붿꽌?쒕뒗 ?꾨옒 ?먮쫫???쇨큵 泥섎━?⑸땲??
     * 1) 媛쒖씤? Lua 1李?李④컧
     * 2) ?꾩슂 ??HYDRATE 蹂듦뎄 ???ъ감媛?
     * 3) ?꾩슂 ??REFILL 寃뚯씠????DB 李④컧/Redis 異⑹쟾 ???ъ감媛?
     *
     * @param payload ?붿껌 ?⑥쐞 而⑦뀓?ㅽ듃(traceId/lineId/familyId/appId ??
     * @param requestedDataBytes ?꾩옱 event?먯꽌 泥섎━?댁빞 ??紐⑺몴 諛붿씠??
     * @return 媛쒖씤? 寃쎈줈 理쒖쥌 Lua ?ㅽ뻾 寃곌낵(answer/status)
     */
    public TrafficLuaExecutionResult executeIndividualWithRecovery(TrafficPayloadReqDto payload, long requestedDataBytes) {
        // 媛쒖씤? 遺꾧린 泥섎━瑜?怨듯넻 硫붿꽌?쒕줈 ?꾩엫??以묐났 肄붾뱶瑜?以꾩씤??
        return executeWithRecovery(TrafficPoolType.INDIVIDUAL, payload, requestedDataBytes);
    }

    /**
     * 怨듭쑀? 李④컧 寃쎈줈瑜??ㅽ뻾?⑸땲??
     *
     * <p>媛쒖씤?怨??숈씪??蹂듦뎄 洹쒖튃(HYDRATE/REFILL)???곸슜?섎릺,
     * ?????뚯쑀???앸퀎?먮뒗 怨듭쑀?(familyId) 湲곗??쇰줈 ?댁꽍?⑸땲??
     *
     * @param payload ?붿껌 ?⑥쐞 而⑦뀓?ㅽ듃
     * @param requestedDataBytes ?꾩옱 event?먯꽌 怨듭쑀????붿껌??紐⑺몴 諛붿씠??
     * @return 怨듭쑀? 寃쎈줈 理쒖쥌 Lua ?ㅽ뻾 寃곌낵(answer/status)
     */
    public TrafficLuaExecutionResult executeSharedWithRecovery(TrafficPayloadReqDto payload, long requestedDataBytes) {
        // 怨듭쑀? 遺꾧린 泥섎━瑜?怨듯넻 硫붿꽌?쒕줈 ?꾩엫??以묐났 肄붾뱶瑜?以꾩씤??
        return executeWithRecovery(TrafficPoolType.SHARED, payload, requestedDataBytes);
    }

    /**
     * ? ???媛쒖씤/怨듭쑀) 怨듯넻 蹂듦뎄 ?ㅼ??ㅽ듃?덉씠?섏쓣 ?섑뻾?⑸땲??
     *
     * <p>吏꾪뻾 ?쒖꽌:
     * 1) payload ?좏슚??寃利??꾩닔 ?앸퀎??traceId/apiTotalData)
     * 2) ??????붾웾 ??怨꾩궛
     * 3) 1李?Lua 李④컧
     * 4) HYDRATE ?꾩슂 ??蹂듦뎄 + 媛숈? event ?ъ떆??
     * 5) NO_BALANCE ??REFILL 遺꾧린 泥섎━
     *
     * @param poolType 泥섎━ ???? ?좏삎
     * @param payload ?붿껌 而⑦뀓?ㅽ듃
     * @param requestedDataBytes ?꾩옱 event 紐⑺몴 諛붿씠??
     * @return 蹂듦뎄 遺꾧린源뚯? 諛섏쁺??理쒖쥌 ?ㅽ뻾 寃곌낵
     */
    private TrafficLuaExecutionResult executeWithRecovery(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            long requestedDataBytes
    ) {
        // ?꾩닔 媛믪씠 鍮꾩뼱 ?덉쑝硫??댄썑 ????怨꾩궛??遺덇??ν븯誘濡?ERROR濡?利됱떆 醫낅즺?쒕떎.
        if (!isPayloadValidForPool(poolType, payload)) {
            return errorResult();
        }

        try {
            trafficLinePolicyHydrationService.ensureLoaded(payload.getLineId());
        } catch (RuntimeException e) {
            log.error(
                    "traffic_line_policy_hydration_failed lineId={}",
                    payload.getLineId(),
                    e
            );
            return errorResult();
        }

        YearMonth targetMonth = resolveTargetMonth(payload);
        String balanceKey = resolveBalanceKey(poolType, payload, targetMonth);

        // 1李?Lua 李④컧 ?ㅽ뻾
        TrafficLuaExecutionResult initialResult = executeDeduct(poolType, payload, balanceKey, requestedDataBytes);

        // HYDRATE 遺꾧린: ??誘몄〈????hydrate -> ?숈씪 event 1???ъ떆??
        TrafficLuaExecutionResult afterHydrateResult = handleHydrateIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                requestedDataBytes,
                initialResult
        );

        // NO_BALANCE 遺꾧린: refill gate/lock ?깃났 ??refill -> ?숈씪 event 1???ъ떆??
        return handleRefillIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                requestedDataBytes,
                afterHydrateResult
        );
    }

    /**
     * ?꾩옱 寃곌낵媛 HYDRATE???뚮쭔 Redis ?붾웾 ?ㅻ? DB 媛믪쑝濡?hydrate?섍퀬 ?ъ떆?꾪빀?덈떎.
     *
     * <p>泥섎━ 洹쒖튃:
     * - status媛 HYDRATE媛 ?꾨땲硫??낅젰 寃곌낵瑜?洹몃?濡?諛섑솚
     * - HYDRATE硫?DB ?먯쿇 ?붾웾?쇰줈 hydrate(`hydrateBalance`) ??媛숈? event?먯꽌 Lua 1???ы샇異?     * - ?ъ떆???꾩뿉??HYDRATE硫?ERROR濡?蹂?섑빐 ?곸쐞?먯꽌 FAILED ?먯젙???????덇쾶 ?쒕떎.
     *
     * @param poolType 泥섎━ ???? ?좏삎
     * @param payload ?붿껌 而⑦뀓?ㅽ듃
     * @param targetMonth ??湲곗? ??怨꾩궛 媛?
     * @param balanceKey Redis ?붾웾 ??
     * @param requestedDataBytes ?꾩옱 event 紐⑺몴 諛붿씠??
     * @param currentResult 1李?Lua 寃곌낵
     * @return hydrate 泥섎━ ??寃곌낵(?먮뒗 ?먮낯 寃곌낵)
     */
    private TrafficLuaExecutionResult handleHydrateIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long requestedDataBytes,
            TrafficLuaExecutionResult currentResult
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.HYDRATE) {
            return currentResult;
        }

        TrafficLuaExecutionResult retriedResult = currentResult;
        String hydrateLockKey = resolveHydrateLockKey(poolType, payload);
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            if (!hydrateLockEnabled) {
                applyHydrate(poolType, payload, targetMonth, balanceKey);
            } else if (tryAcquireHydrateLock(hydrateLockKey, payload.getTraceId())) {
                try {
                    applyHydrate(poolType, payload, targetMonth, balanceKey);
                } finally {
                    trafficLuaScriptInfraService.executeLockRelease(hydrateLockKey, payload.getTraceId());
                }
            } else {
                // ?ㅻⅨ ?몄뒪?댁뒪媛 hydrate 以묒씪 ???덉쑝誘濡??좉퉸 ?湲고븳 ??李④컧 ?ъ떆?꾨? 1???섑뻾?쒕떎.
                sleepHydrateLockWait();
            }

            retriedResult = executeDeduct(poolType, payload, balanceKey, requestedDataBytes);
            if (retriedResult.getStatus() != TrafficLuaStatus.HYDRATE) {
                // HYDRATE?먯꽌 踰쀬뼱?섎㈃ 利됱떆 寃곌낵瑜?諛섑솚?쒕떎.
                return retriedResult;
            }
        }

        // ?ъ떆???꾩뿉??HYDRATE硫?蹂듦뎄 ?ㅽ뙣濡?媛꾩＜?섍퀬 ERROR濡?蹂?섑븳??
        log.error(
                "traffic_hydrate_retry_exhausted poolType={} traceId={} balanceKey={}",
                poolType,
                payload.getTraceId(),
                balanceKey
        );
        return errorResult();
    }

    /**
     * HYDRATE ?곹깭?먯꽌 ?붾웾 ?ㅻ? DB ?먯쿇媛믪쑝濡?蹂듦뎄?섍퀬 媛쒖씤? QoS瑜??숆린?뷀빀?덈떎.
     */
    private void applyHydrate(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey
    ) {
        long initialAmount = trafficQuotaSourcePort.loadInitialAmount(poolType, payload, targetMonth);
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);
        trafficQuotaCacheService.hydrateBalance(balanceKey, initialAmount, monthlyExpireAt);
        if (poolType == TrafficPoolType.INDIVIDUAL) {
            // 媛쒖씤? ?붾웾 ?댁떆??QoS瑜??④퍡 湲곕줉??Lua/?꾩냽 泥섎━?먯꽌 利됱떆 李몄“?????덇쾶 ?쒕떎.
            long qosSpeedLimit = trafficQuotaSourcePort.loadIndividualQosSpeedLimit(payload);
            trafficQuotaCacheService.putQos(balanceKey, qosSpeedLimit);
        }
        trafficHydrateMetrics.incrementHydrate(poolType);
    }

    /**
     * ?꾩옱 寃곌낵媛 NO_BALANCE????REFILL 寃뚯씠????DB 李④컧/Redis 異⑹쟾???섑뻾?⑸땲??
     *
     * <p>?듭떖 ?먮쫫:
     * 1) NO_BALANCE媛 ?꾨땲硫?洹몃?濡?諛섑솚
     * 2) 理쒖떊 踰꾪궥 湲곕컲 由ы븘 怨꾪쉷(delta/unit/threshold) 怨꾩궛
     * 3) refill_gate.lua濡?由ы븘 吏꾩엯 媛???щ? ?뺤씤(OK留?吏꾪뻾)
     * 4) lock heartbeat濡??뚯쑀沅??뺤씤(?뚯쑀?먮쭔 吏꾪뻾)
     * 5) DB?먯꽌 actualRefillAmount ?뺣낫(min(requested, dbRemaining))
     * 6) actualRefillAmount > 0 ?대㈃ Redis 異⑹쟾 ??"?⑥? event 紐⑺몴??residual)" ?ъ감媛?1??
     * 7) finally?먯꽌 lock ?댁젣 蹂댁옣
     *
     * @param poolType 泥섎━ ???? ?좏삎
     * @param payload ?붿껌 而⑦뀓?ㅽ듃
     * @param targetMonth ??湲곗? ??怨꾩궛 媛?
     * @param balanceKey Redis ?붾웾 ??
     * @param requestedDataBytes ?꾩옱 event 紐⑺몴 諛붿씠??
     * @param currentResult HYDRATE 泥섎━ ?댄썑 ?꾩옱 寃곌낵
     * @return refill 泥섎━ ?댄썑 寃곌낵(?먮뒗 ?먮낯 寃곌낵)
     */
    private TrafficLuaExecutionResult handleRefillIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long requestedDataBytes,
            TrafficLuaExecutionResult currentResult
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.NO_BALANCE) {
            return currentResult;
        }

        String lockKey = resolveLockKey(poolType, payload);
        long normalizedRequestedDataBytes = Math.max(0L, requestedDataBytes);
        long firstDeductedAmount = normalizeNonNegative(currentResult.getAnswer());
        long retryTargetData = clampRemaining(normalizedRequestedDataBytes - firstDeductedAmount);

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
                    "traffic_refill_plan_resolved poolType={} balanceKey={} currentAmount={} delta={} bucketCount={} refillUnit={} threshold={} source={}",
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
                    balanceKey,
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    currentAmount,
                    threshold
            );

            if (gateStatus != TrafficRefillGateStatus.OK) {
                // WAIT/SKIP/FAIL?대㈃ ?꾩옱 event?먯꽌 由ы븘??吏꾪뻾?섏? ?딄퀬 湲곗〈 寃곌낵瑜??좎??쒕떎.
                log.debug(
                        "traffic_refill_gate_not_ok poolType={} gateStatus={}",
                        poolType,
                        gateStatus
                );
                trafficRefillMetrics.increment(poolType.name(), "gate_" + gateStatus.name().toLowerCase());
                return retriedResult;
            }

            boolean lockOwned = trafficLuaScriptInfraService.executeLockHeartbeat(
                    lockKey,
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS
            );

            if (!lockOwned) {
                // lock ?뚯쑀沅뚯씠 ?놁쑝硫??숈떆??異⑸룎 媛?μ꽦???덉뼱 由ы븘??嫄대꼫?대떎.
                log.debug(
                        "traffic_refill_lock_not_owned poolType={} lockKey={}",
                        poolType,
                        lockKey
                );
                trafficRefillMetrics.increment(poolType.name(), "lock_not_owned");
                return retriedResult;
            }

            try {
                TrafficDbRefillClaimResult claimResult = claimRefillAmountFromDbWithRetry(
                        poolType,
                        payload,
                        targetMonth,
                        requestedRefillUnit
                );
                long dbRemainingBefore = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingBefore());
                long actualRefillAmount = normalizeNonNegative(claimResult == null ? null : claimResult.getActualRefillAmount());
                long dbRemainingAfter = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingAfter());
                trafficQuotaCacheService.writeDbEmptyFlag(balanceKey, dbRemainingAfter <= 0);
                if (actualRefillAmount <= 0) {
                    // DB?먯꽌 ?ㅼ젣 李④컧???묒씠 ?놁쑝硫?Redis 異⑹쟾 ?놁씠 ?꾩옱 寃곌낵瑜??좎??쒕떎.
                    log.debug(
                            "traffic_refill_db_noop poolType={} requestedRefill={} threshold={} delta={} bucketCount={} source={} dbBefore={} actualRefill={} dbAfter={}",
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
                    trafficRefillMetrics.increment(poolType.name(), "db_noop");
                    return retriedResult;
                }

                long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);

                // 由ы븘 ?묒뾽 ?숈븞 lock TTL??留뚮즺?섏? ?딅룄濡?heartbeat瑜??쒕쾲 ???섑뻾?쒕떎.
                boolean lockStillOwned = trafficLuaScriptInfraService.executeLockHeartbeat(
                        lockKey,
                        payload.getTraceId(),
                        TrafficRedisRuntimePolicy.LOCK_TTL_MS
                );
                if (!lockStillOwned) {
                    log.warn(
                            "traffic_refill_lock_lost_before_redis_apply poolType={} lockKey={}",
                            poolType,
                            lockKey
                    );
                    trafficRefillMetrics.increment(poolType.name(), "lock_lost");
                    return retriedResult;
                }
                trafficQuotaCacheService.refillBalance(balanceKey, actualRefillAmount, monthlyExpireAt);
                log.info(
                        "traffic_refill_applied poolType={} balanceKey={} requestedRefill={} threshold={} delta={} bucketCount={} source={} dbBefore={} actualRefill={} dbAfter={}",
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
                trafficRefillMetrics.increment(poolType.name(), "refill_applied");

                // 由ы븘 ?꾩뿉??"?⑥? 紐⑺몴??residual)"留?1???ъ감媛먰빐 怨쇱감媛먯쓣 諛⑹??쒕떎.
                TrafficLuaExecutionResult refillRetryResult = executeDeduct(
                        poolType,
                        payload,
                        balanceKey,
                        retryTargetData
                );
                retriedResult = mergeRefillRetryResult(
                        normalizedRequestedDataBytes,
                        firstDeductedAmount,
                        refillRetryResult
                );
                return retriedResult;
            } catch (RuntimeException e) {
                // DB claim ?④퀎???덉쇅???꾩옱 event?먯꽌 由ы븘留?以묐떒?섍퀬 湲곗〈 NO_BALANCE 寃곌낵瑜??좎??쒕떎.
                log.error(
                        "traffic_refill_db_claim_failed poolType={} balanceKey={} traceId={}",
                        poolType,
                        balanceKey,
                        payload.getTraceId(),
                        e
                );
                trafficRefillMetrics.increment(poolType.name(), "db_error");
                return retriedResult;
            } finally {
                // ?깃났/?ㅽ뙣? 臾닿??섍쾶 lock? 諛섎뱶???뚯쑀??湲곗??쇰줈 ?댁젣?쒕떎.
                trafficLuaScriptInfraService.executeLockRelease(lockKey, payload.getTraceId());
            }
        }

        return retriedResult;
    }

    /**
     * NO_BALANCE 1李?李④컧?됯낵 由ы븘 ???ъ감媛먮웾???⑹궛??媛숈? event 理쒖쥌 李④컧?됱쑝濡??뺢퇋?뷀빀?덈떎.
     */
    private TrafficLuaExecutionResult mergeRefillRetryResult(
            long requestedDataBytes,
            long firstDeductedAmount,
            TrafficLuaExecutionResult refillRetryResult
    ) {
        long retriedDeductedAmount = normalizeNonNegative(refillRetryResult == null ? null : refillRetryResult.getAnswer());
        long mergedDeductedAmount = clampToMax(requestedDataBytes, safeAdd(firstDeductedAmount, retriedDeductedAmount));
        TrafficLuaStatus mergedStatus = refillRetryResult == null
                ? TrafficLuaStatus.ERROR
                : refillRetryResult.getStatus();

        return TrafficLuaExecutionResult.builder()
                .answer(mergedDeductedAmount)
                .status(mergedStatus)
                .build();
    }

    /**
     * ? ?좏삎??留욌뒗 李④컧 Lua瑜??ㅽ뻾?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param balanceKey ????붾웾 Redis ??
     * @param requestedDataBytes ?꾩옱 event 紐⑺몴 諛붿씠??
     * @return Lua 李④컧 寃곌낵(answer/status)
     */
    private TrafficLuaExecutionResult executeDeduct(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            String balanceKey,
            long requestedDataBytes
    ) {
        // ?뺤콉 寃뚯씠???ъ슜???ㅻ? Lua?먯꽌 ?④퍡 泥섎━?????덈룄濡??꾩옱 ?쒓컖 湲곕컲 ?뚯깮 ?ㅻ? 援ъ꽦?쒕떎.
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

        // ? ?좏삎??留욌뒗 Lua ?ㅽ겕由쏀듃瑜??좏깮??李④컧 ?ㅽ뻾?쒕떎.
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
                        String.valueOf(requestedDataBytes),
                        String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum),
                        String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond),
                        String.valueOf(dailyExpireAt)
                );
                yield trafficLuaScriptInfraService.executeDeductIndividual(keys, args);
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
                        String.valueOf(requestedDataBytes),
                        String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum),
                        String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond),
                        String.valueOf(dailyExpireAt),
                        String.valueOf(monthlyExpireAt)
                );
                yield trafficLuaScriptInfraService.executeDeductShared(keys, args);
            }
        };
    }

    /**
     * ? ?좏삎怨???湲곗??쇰줈 Redis ?붾웾 ?ㅻ? ?앹꽦?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 ?앸퀎??lineId/familyId) ?ы븿 而⑦뀓?ㅽ듃
     * @param targetMonth ??suffix(yyyymm) 怨꾩궛 湲곗? ??
     * @return remaining_indiv/shared_amount ??
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        // ? ?좏삎留덈떎 ?붾웾 ??援ъ“媛 ?ㅻⅤ誘濡?遺꾧린???앹꽦?쒕떎.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
    }

    /**
     * ? ?좏삎 湲곗??쇰줈 refill lock ?ㅻ? ?앹꽦?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 ?앸퀎??lineId/familyId) ?ы븿 而⑦뀓?ㅽ듃
     * @return indiv/shared refill lock ??
     */
    private String resolveLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 由ы븘 lock ?ㅻ룄 ? ?좏삎留덈떎 ?ㅻⅤ誘濡?遺꾧린???앹꽦?쒕떎.
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivRefillLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedRefillLockKey(payload.getFamilyId());
        };
    }

    /**
     * ? ?좏삎 湲곗??쇰줈 hydrate lock ?ㅻ? ?앹꽦?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 ?앸퀎??lineId/familyId) ?ы븿 而⑦뀓?ㅽ듃
     * @return indiv/shared hydrate lock ??
     */
    private String resolveHydrateLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivHydrateLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedHydrateLockKey(payload.getFamilyId());
        };
    }

    /**
     * HYDRATE ?좏뻾 ?ㅽ뻾???⑥씪 ?몄뒪?댁뒪濡??쒗븳?섍린 ?꾪빐 遺꾩궛???띾뱷???쒕룄?⑸땲??
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
     * hydrate ??誘명쉷?????ㅻⅨ ?몄뒪?댁뒪??泥섎━ ?꾨즺瑜??좉퉸 湲곕떎由쎈땲??
     */
    private void sleepHydrateLockWait() {
        long waitMs = Math.max(0L, hydrateLockWaitMs);
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ?붿껌???랁븳 湲곗? ??YearMonth)??寃곗젙?⑸땲??
     *
     * <p>?뺥빀??洹쒖튃???곕씪 媛?ν븯硫?payload.enqueuedAt???곗꽑 ?ъ슜?섍퀬,
     * 媛믪씠 ?녾굅??鍮꾩젙?곸씠硫??고????꾩옱 ?쒓컖(Asia/Seoul) 湲곗??쇰줈 ?泥댄빀?덈떎.
     *
     * @param payload ?붿껌 而⑦뀓?ㅽ듃
     * @return DB/Redis ?????뺥빀?깆뿉 ?ъ슜??湲곗? ??
     */
    private YearMonth resolveTargetMonth(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            // enqueue ?쒓컖???놁쑝硫??꾩옱 ?쒓컖(Asia/Seoul) 湲곗? ?붿쓣 ?ъ슜?쒕떎.
            return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        }

        // payload???닿릿 enqueue ?쒓컖??湲곗??쇰줈 ????yyyymm)瑜?怨꾩궛?쒕떎.
        return YearMonth.from(Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()));
    }

    /**
     * ? 泥섎━???꾩슂???꾩닔 payload 媛믪씠 紐⑤몢 ?덈뒗吏 寃利앺빀?덈떎.
     *
     * <p>怨듯넻 ?꾩닔媛? traceId, lineId, appId, apiTotalData(0 ?댁긽)<br>
     * ?蹂??꾩닔媛? INDIVIDUAL=lineId, SHARED=familyId(+lineId 怨듯넻 ?꾩닔)
     *
     * @param poolType 寃利????? ?좏삎
     * @param payload 寃利앺븷 ?붿껌 而⑦뀓?ㅽ듃
     * @return ?좏슚?섎㈃ true, ?꾨씫/鍮꾩젙?곸씠 ?덉쑝硫?false
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

        // ?蹂????앹꽦???꾩슂???앸퀎?먭? ?놁쑝硫?泥섎━?????녿떎.
        return switch (poolType) {
            case INDIVIDUAL -> true;
            case SHARED -> payload.getFamilyId() != null && payload.getFamilyId() > 0;
        };
    }

    /**
     * ?좏슚???ㅽ뙣 ??利됱떆 醫낅즺 ?곹솴?먯꽌 ?ъ슜?섎뒗 ?쒖? ERROR 寃곌낵瑜??앹꽦?⑸땲??
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
     * Long 媛믪쓣 ?뚯닔/NULL 諛⑹뼱 洹쒖튃?쇰줈 0 ?댁긽 媛믪쑝濡?蹂댁젙?⑸땲??
     *
     * @param value 蹂댁젙 ???媛?
     * @return 0 ?댁긽 ?뺢퇋??媛?
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * Integer 媛믪쓣 ?뚯닔/NULL 諛⑹뼱 洹쒖튃?쇰줈 0 ?댁긽 媛믪쑝濡?蹂댁젙?⑸땲??
     *
     * @param value 蹂댁젙 ???媛?
     * @return 0 ?댁긽 ?뺢퇋??媛?
     */
    private int normalizeNonNegativeInt(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value;
    }

    /**
     * ?뚯닔 寃곌낵媛 ?섏삱 ???덈뒗 類꾩뀍 ?곗궛 寃곌낵瑜?0 ?댁긽?쇰줈 蹂댁젙?⑸땲??
     */
    private long clampRemaining(long value) {
        if (value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * ?꾩쟻 李④컧?됱씠 event 紐⑺몴?됱쓣 ?섏? ?딅룄濡??곹븳???곸슜?⑸땲??
     */
    private long clampToMax(long maxValue, long value) {
        long normalizedMaxValue = Math.max(0L, maxValue);
        if (value <= 0) {
            return 0L;
        }
        return Math.min(normalizedMaxValue, value);
    }

    /**
     * long ?㏃뀍 ?ㅻ쾭?뚮줈?곕? 諛⑹뼱?섎ŉ ?꾩쟻 媛믪쓣 怨꾩궛?⑸땲??
     */
    private long safeAdd(long left, long right) {
        if (left <= 0) {
            return Math.max(0L, right);
        }
        if (right <= 0) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * DB refill claim??理쒕? 2???ъ떆??珥?3???쒕룄) 洹쒖튃?쇰줈 ?ㅽ뻾?⑸땲??
     */
    private TrafficDbRefillClaimResult claimRefillAmountFromDbWithRetry(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            long requestedRefillAmount
    ) {
        RuntimeException lastException = null;
        for (int retryCount = 0; retryCount <= DB_RETRY_MAX; retryCount++) {
            try {
                return trafficQuotaSourcePort.claimRefillAmountFromDb(
                        poolType,
                        payload,
                        targetMonth,
                        requestedRefillAmount
                );
            } catch (RuntimeException e) {
                lastException = e;
                boolean retryable = isRetryableDbException(e);
                if (!retryable || retryCount >= DB_RETRY_MAX) {
                    throw e;
                }

                log.warn(
                        "traffic_refill_db_retry poolType={} traceId={} retry={}/{}",
                        poolType,
                        payload.getTraceId(),
                        retryCount + 1,
                        DB_RETRY_MAX
                );
                sleepDbRetryBackoff();
            }
        }

        throw lastException == null
                ? new IllegalStateException("traffic_refill_db_retry_exhausted")
                : lastException;
    }

    /**
     * deadlock/lock wait timeout ?깃꺽???쇱떆??DB ?덉쇅?몄? ?먮퀎?⑸땲??
     */
    private boolean isRetryableDbException(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("deadlock") || normalized.contains("lock wait timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * DB ?ъ떆??媛?吏㏃? backoff瑜??곸슜?⑸땲??
     */
    private void sleepDbRetryBackoff() {
        try {
            Thread.sleep(DB_RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
