package com.pooli.traffic.service.decision;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.monitoring.metrics.TrafficHydrateMetrics;
import com.pooli.monitoring.metrics.TrafficRefillMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
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
 * 트래픽 차감의 "deduct + hydrate + refill" 단계를 단일 흐름으로 오케스트레이션하는 어댑터 서비스입니다.
 *
 * <p>주요 책임:
 * 1) 차감 단계 Lua 실행(개인/공유)
 * 2) 상태값(GLOBAL_POLICY_HYDRATE, HYDRATE, NO_BALANCE)별 hydrate/refill 분기
 * 3) NO_BALANCE 시 refill gate/락/DB claim/outbox/재차감 연계
 *
 * <p>차단성 정책 검증/정책 단계 fallback 전환은 오케스트레이터가 담당하며,
 * 본 어댑터는 전달받은 실행 컨텍스트(예: whitelist bypass 플래그)만 소비합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficHydrateRefillAdapterService {

    private static final int HYDRATE_RETRY_MAX = 5;
    private static final int REFILL_RETRY_MAX = 1;
    private static final int DB_RETRY_MAX = 3;
    private static final long POLICY_REPEAT_BLOCK_ID = 1L;
    private static final long POLICY_IMMEDIATE_BLOCK_ID = 2L;
    private static final long POLICY_LINE_LIMIT_SHARED_ID = 3L;
    private static final long POLICY_LINE_LIMIT_DAILY_ID = 4L;
    private static final long POLICY_APP_DATA_ID = 5L;
    private static final long POLICY_APP_SPEED_ID = 6L;
    private static final long POLICY_APP_WHITELIST_ID = 7L;

    @Value("${app.traffic.hydrate-lock.enabled:true}")
    private boolean hydrateLockEnabled;

    @Value("${app.traffic.deduct.redis-retry.max-attempts:3}")
    private int redisRetryMaxAttempts;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long redisRetryBackoffMs;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficQuotaSourcePort trafficQuotaSourcePort;
    private final TrafficQuotaCacheService trafficQuotaCacheService;
    private final TrafficPolicyBootstrapService trafficPolicyBootstrapService;
    private final TrafficHydrateMetrics trafficHydrateMetrics;
    private final TrafficRefillMetrics trafficRefillMetrics;
    private final TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;
    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

    /**
     * 개인풀 차감의 실행 + hydrate/refill/fallback 흐름을 실행합니다.
     */
    public TrafficLuaExecutionResult executeIndividualWithRecovery(TrafficPayloadReqDto payload, long requestedDataBytes) {
        return executeIndividualWithRecovery(
                payload,
                requestedDataBytes,
                TrafficDeductExecutionContext.of(payload == null ? null : payload.getTraceId())
        );
    }

    /**
     * 개인풀 차감의 실행 + hydrate/refill/fallback 흐름을 실행합니다.
     * 같은 traceId 처리 내 재시도/DB fallback 전환 상태를 context로 공유합니다.
     */
    public TrafficLuaExecutionResult executeIndividualWithRecovery(
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context
    ) {
        return executeWithRecovery(TrafficPoolType.INDIVIDUAL, payload, requestedDataBytes, context);
    }

    /**
     * 공유풀 차감의 실행 + hydrate/refill/fallback 흐름을 실행합니다.
     */
    public TrafficLuaExecutionResult executeSharedWithRecovery(TrafficPayloadReqDto payload, long requestedDataBytes) {
        return executeSharedWithRecovery(
                payload,
                requestedDataBytes,
                TrafficDeductExecutionContext.of(payload == null ? null : payload.getTraceId())
        );
    }

    /**
     * 공유풀 차감의 실행 + hydrate/refill/fallback 흐름을 실행합니다.
     * 같은 traceId 처리 내 재시도/DB fallback 전환 상태를 context로 공유합니다.
     */
    public TrafficLuaExecutionResult executeSharedWithRecovery(
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context
    ) {
        return executeWithRecovery(TrafficPoolType.SHARED, payload, requestedDataBytes, context);
    }

    /**
     * 풀 유형(개인/공유)에 맞는 차감 실행 뒤 hydrate/refill 흐름을 순차적으로 실행합니다.
     * 
     * [주요 흐름]
     * 1. 페이로드 유효성 검사
     * 2. 차감 단계 차감 시도 (executeDeduct)
     * 3. 결과 상태에 따른 단계별 hydrate/refill 시도:
     *    - GLOBAL_POLICY_HYDRATE: 전역 정책 스냅샷 hydrate 후 재시도
     *    - HYDRATE: DB 원본 잔량 및 QoS 정보를 Redis로 로드 후 재시도
     *    - NO_BALANCE: DB에서 추가 잔량을 확보(refill)한 뒤 재시도
     * 
     * @param poolType 차감 대상 풀 유형
     * @param payload 요청 페이로드
     * @param requestedDataBytes 요청된 데이터 양 (byte)
     * @param context 재시도 및 traceId 범위 실행 상태를 전달하는 컨텍스트
     * @return 최종 Lua 실행 결과
     */
    private TrafficLuaExecutionResult executeWithRecovery(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context
    ) {
        // 1. 입력 페이로드의 필수 값이 풀 유형에 맞게 포함되어 있는지 검사합니다.
        if (!isPayloadValidForPool(poolType, payload)) {
            return errorResult();
        }

        YearMonth targetMonth = resolveTargetMonth(payload);
        String balanceKey = resolveBalanceKey(poolType, payload, targetMonth);
        // 오케스트레이터 선검증 결과(whitelist bypass 플래그)가 있으면 소비하고, 없으면 기본값 0을 사용합니다.
        int whitelistBypassFlag = resolveWhitelistBypassFlag(context);

        // 2. 차감 단계를 실행합니다. Redis 연결 문제는 재시도 후 상위로 재전파합니다.
        TrafficLuaExecutionResult initialResult = executeDeduct(
                poolType,
                payload,
                balanceKey,
                requestedDataBytes,
                context,
                whitelistBypassFlag,
                TrafficFailureStage.DEDUCT,
                RefillLuaArguments.none()
        );

        // 3. 리턴된 상태가 GLOBAL_POLICY_HYDRATE인 경우, 유실된 전역 정책 스냅샷을 복구하고 재시도합니다.
        TrafficLuaExecutionResult afterGlobalPolicyHydrateResult = handleGlobalPolicyHydrateIfNeeded(
                poolType,
                payload,
                balanceKey,
                requestedDataBytes,
                initialResult,
                context,
                whitelistBypassFlag
        );

        // 4. 리턴된 상태가 HYDRATE인 경우, Redis 캐시에 잔량 정보가 없으므로 DB 원본 데이터를 로드하고 재시도합니다.
        TrafficLuaExecutionResult afterHydrateResult = handleHydrateIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                requestedDataBytes,
                afterGlobalPolicyHydrateResult,
                context,
                whitelistBypassFlag
        );

        // 5. 리턴된 상태가 NO_BALANCE인 경우, DB에서 잔량을 차감(리필)하여
        // "리필 반영 + 재차감"을 재차감 Lua 1회로 같이 수행합니다.
        return handleRefillIfNeeded(
                poolType,
                payload,
                targetMonth,
                balanceKey,
                requestedDataBytes,
                afterHydrateResult,
                context,
                whitelistBypassFlag
        );
    }

    /**
     * 오케스트레이터가 기록한 정책 검증 결과에서 whitelist bypass 플래그를 추출합니다.
     * 컨텍스트가 비어 있거나 결과가 없으면 기본값 0(우회 비활성)을 반환합니다.
     *
     * <p>
     * ==== 반환값 정의 ====
     * <p>
     *  0: 화이트리스트 우회 비활성<br>
     *   - 컨텍스트 없음<br>
     *   - 정책 검증 결과 없음<br>
     *   - 정책 검증 상태가 OK가 아님(차단/오류)<br>
     *   - 정책 결과 answer가 0 이하
     * </p>
     * <p>
     *  1: 화이트리스트 우회 활성<br>
     *   - 정책 검증 상태가 OK이고 정책 결과 answer가 1 이상
     * </p>
     * </p>
     * <p>
     * ==== 의미 ====<br>
     * - 0: 차감 Lua가 일반 정책 검증 경로를 사용합니다.<br>
     * - 1: 차감 Lua에 whitelist bypass 인자를 전달해 정책 우회 경로를 사용합니다.
     * </p>
     */
    private int resolveWhitelistBypassFlag(TrafficDeductExecutionContext context) {
        if (context == null) {
            return 0;
        }
        TrafficLuaExecutionResult cachedPolicyCheckResult = context.getBlockingPolicyCheckResult();
        if (cachedPolicyCheckResult == null) {
            return 0;
        }
        if (cachedPolicyCheckResult.getStatus() != TrafficLuaStatus.OK) {
            return 0;
        }
        return normalizeNonNegative(cachedPolicyCheckResult.getAnswer()) > 0 ? 1 : 0;
    }

    /**
     * GLOBAL_POLICY_HYDRATE 상태일 때 전역 정책 스냅샷을 다시 적재한 뒤 동일 Lua를 재시도합니다.
     */
    /**
     * 전역 정책 데이터(Global Policy)가 유실된 경우(GLOBAL_POLICY_HYDRATE 상태) 스냅샷을 다시 적재하고 차감을 재시도합니다.
     * 
     * [상세 로직]
     * 1. HYDRATE_RETRY_MAX(5회)만큼 반복 시도합니다.
     * 2. trafficPolicyBootstrapService.hydrateOnDemand() 호출을 통해 Redis에 전역 정책 데이터를 전파합니다.
     * 3. 복구 성공 여부와 상관없이 executeDeduct를 통해 차감을 재시도하여 상태 변화를 체크합니다.
     * 4. 재시도 간격(100ms)을 두어 Redis 전파 시간을 확보합니다.
     */
    private TrafficLuaExecutionResult handleGlobalPolicyHydrateIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            String balanceKey,
            long requestedDataBytes,
            TrafficLuaExecutionResult currentResult,
            TrafficDeductExecutionContext context,
            int whitelistBypassFlag
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
            return currentResult;
        }

        TrafficLuaExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            try {
                // 기존 bootstrap lock 규칙을 재사용해 전역 정책 전체 스냅샷을 보정합니다.
                // 이 과정에서 Redis에 정책 메타데이터가 없는 경우 스냅샷에서 데이터를 읽어와 채웁니다.
                trafficPolicyBootstrapService.hydrateOnDemand();
            } catch (ApplicationException | DataAccessException e) {
                log.error(
                        "traffic_hydrate_global_policy_failed poolType={} traceId={} retry={}",
                        poolType,
                        payload.getTraceId(),
                        retry + 1,
                        e
                );
            }

            // 정책 보정 후 차감을 다시 시도하여 상태를 업데이트합니다.
            retriedResult = executeDeduct(
                    poolType,
                    payload,
                    balanceKey,
                    requestedDataBytes,
                    context,
                    whitelistBypassFlag,
                    TrafficFailureStage.HYDRATE,
                    RefillLuaArguments.none()
            );
            if (retriedResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
                return retriedResult; // 성공(OK)하거나 다른 상태(HYDRATE 등)로 전이된 경우 즉시 반환합니다.
            }
            sleepHydrateRetryBackoff(retry + 1); // Redis 데이터 전파 및 동기화를 위한 짧은 대기 시간을 갖습니다.
        }

        log.error(
                "traffic_hydrate_global_policy_retry_exhausted poolType={} traceId={} balanceKey={}",
                poolType,
                payload.getTraceId(),
                balanceKey
        );
        return retriedResult;
    }

    /**
     * Redis 캐시에 잔액 정보가 없는 경우(HYDRATE 상태) DB에서 원본 정보를 가져와 Redis를 채운 뒤 차감을 재시도합니다.
     * 
     * [상세 로직]
     * 1. 중복 동기화를 방지하기 위해 분산 락(Hydrate Lock)을 사용합니다.
     * 2. 락 획득 시: DB 원본 데이터 로드 및 Redis 반영(applyHydrate) 후 락 해제.
     * 3. 락 획득 실패 시: 다른 노드/스레드가 동기화 중이므로 잠시 대기 후 차감 재시도.
     * 4. 최대 5회 재시도 후에도 데이터가 없는 경우 마지막 결과(HYDRATE)를 반환합니다.
     */
    private TrafficLuaExecutionResult handleHydrateIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long requestedDataBytes,
            TrafficLuaExecutionResult currentResult,
            TrafficDeductExecutionContext context,
            int whitelistBypassFlag
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.HYDRATE) {
            return currentResult;
        }

        TrafficLuaExecutionResult retriedResult = currentResult;
        String hydrateLockKey = resolveHydrateLockKey(poolType, payload);
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            if (!hydrateLockEnabled) {
                // 락이 비활성화된 경우(설정값 기준) 즉시 동기화를 수행합니다.
                applyHydrate(poolType, payload, targetMonth, balanceKey);
            } else if (tryAcquireHydrateLock(hydrateLockKey, payload.getTraceId())) {
                // 락 획득에 성공한 경우에만 DB -> Redis 동기화를 직접 수행합니다.
                try {
                    applyHydrate(poolType, payload, targetMonth, balanceKey);
                } finally {
                    // 동기화 완료 후 락을 해제합니다.
                    trafficLuaScriptInfraService.executeLockRelease(hydrateLockKey, payload.getTraceId());
                }
            } else {
                // 락 획득 실패 시, 다른 요청이 동기화를 완료할 때까지 잠시 대기합니다.
                sleepHydrateRetryBackoff(retry + 1);
            }

            // 동기화 시도 후 다시 차감을 통해 성공 여부나 다음 상태를 체크합니다.
            retriedResult = executeDeduct(
                    poolType,
                    payload,
                    balanceKey,
                    requestedDataBytes,
                    context,
                    whitelistBypassFlag,
                    TrafficFailureStage.HYDRATE,
                    RefillLuaArguments.none()
            );
            if (retriedResult.getStatus() != TrafficLuaStatus.HYDRATE) {
                return retriedResult; // 데이터가 채워져 성공하거나 다른 단계로 넘어간 경우 반환합니다.
            }
        }

        log.error(
                "traffic_hydrate_retry_exhausted poolType={} traceId={} balanceKey={}",
                poolType,
                payload.getTraceId(),
                balanceKey
        );
        return retriedResult;
    }

    /**
     * DB 원본 잔량과 QoS 정보를 Redis 캐시에 반영합니다.
     */
    private void applyHydrate(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey
    ) {
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);
        trafficQuotaCacheService.hydrateBalance(balanceKey, monthlyExpireAt);
        if (poolType == TrafficPoolType.INDIVIDUAL) {
            long qosSpeedLimit = trafficQuotaSourcePort.loadIndividualQosSpeedLimit(payload);
            trafficQuotaCacheService.putQos(balanceKey, qosSpeedLimit);
        }
        trafficHydrateMetrics.incrementHydrate(poolType);
    }

    /**
     * NO_BALANCE 상태일 때 리필 게이트와 DB 차감을 거쳐 재차감합니다.
     */
    /**
     * 잔액 부족(NO_BALANCE 상태) 시 DB에서 추가 잔량을 확보(리필)하고 Redis를 갱신한 뒤 차감을 다시 시도합니다.
     * 이 서비스에서 가장 복잡한 흐름을 제어하며, 게이트 키퍼와 분산 락을 통해 DB 부하 및 동시성을 제어합니다.
     * 
     * [상세 로직 - '리필 오케스트레이션']
     * 1. 리필 게이트(Refill Gate) 체크: 불필요한 DB 접근을 막기 위해 게이트 상태(OK, WAIT, SKIP_DB_EMPTY, SKIP_THRESHOLD)를 먼저 확인합니다.
     * 2. 리필 락(Refill Lock) 획득/유지: 한 번에 하나의 프로세스만 특정 회선의 리필을 수행하도록 제어합니다.
     * 3. DB 리필 요청(claimRefillAmountFromDb): DB에서 실제 가용 데이터 잔량을 차감(Claim)합니다. 이 과정은 별도의 Outbox에 기록됩니다.
     * 4. Redis 멱등 적용(applyRefillWithIdempotency): DB에서 가져온 양만큼 Redis 잔액을 늘립니다. 
     *    - 네트워크 장애 등으로 인한 중복 반영을 막기 위해 UUID 기반 멱등 키를 사용합니다.
     * 5. Outbox 및 멱등 완료 처리: 성공 시 Outbox 레코드를 성공 상태로 만들고 사용한 멱등 키를 정리합니다.
     * 6. 재차감(Retry Deduct): 리필된 잔액을 바탕으로 원래 요청했던 차감 처리를 마무리합니다.
     */
    private TrafficLuaExecutionResult handleRefillIfNeeded(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            String balanceKey,
            long requestedDataBytes,
            TrafficLuaExecutionResult currentResult,
            TrafficDeductExecutionContext context,
            int whitelistBypassFlag
    ) {
        if (currentResult.getStatus() != TrafficLuaStatus.NO_BALANCE) {
            return currentResult;
        }

        String lockKey = resolveLockKey(poolType, payload);
        long normalizedRequestedDataBytes = Math.max(0L, requestedDataBytes);
        // 1차 차감에서 이미 차감된 양을 제외한 순수 '부족한 양'을 계산합니다.
        long firstDeductedAmount = normalizeNonNegative(currentResult.getAnswer());
        long retryTargetData = clampRemaining(normalizedRequestedDataBytes - firstDeductedAmount);

        TrafficLuaExecutionResult retriedResult = currentResult;
        for (int retry = 0; retry < REFILL_RETRY_MAX; retry++) {
            // [Step 1] 리필 계획 및 게이트 체크
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

            // 게이트 상태를 확인하여 리필 진행 여부를 결정합니다.
            TrafficRefillGateStatus gateStatus = trafficLuaScriptInfraService.executeRefillGate(
                    lockKey,
                    balanceKey,
                    payload.getTraceId(),
                    TrafficRedisRuntimePolicy.LOCK_TTL_MS,
                    currentAmount,
                    threshold
            );

            if (gateStatus != TrafficRefillGateStatus.OK) {
                // OK가 아닌 경우는 기본적으로 리필을 진행하지 않고 현재의 NO_BALANCE 결과를 유지합니다.
                log.debug(
                        "traffic_refill_gate_not_ok poolType={} gateStatus={}",
                        poolType,
                        gateStatus
                );
                String gateMetricResult = "gate_" + gateStatus.name().toLowerCase();
                trafficRefillMetrics.increment(poolType.name(), gateMetricResult);
                // 기존 대시보드 호환을 위해 상세 SKIP과 함께 집계용 gate_skip도 유지합니다.
                if (gateStatus == TrafficRefillGateStatus.SKIP_DB_EMPTY
                        || gateStatus == TrafficRefillGateStatus.SKIP_THRESHOLD) {
                    trafficRefillMetrics.increment(poolType.name(), "gate_skip");
                }

                // 공유풀은 DB 고갈(SKIP_DB_EMPTY)로 리필이 닫혔을 때만 QoS fallback을 1회 허용합니다.
                // WAIT/SKIP_THRESHOLD에서는 기존대로 즉시 NO_BALANCE를 유지합니다.
                if (poolType == TrafficPoolType.SHARED
                        && gateStatus == TrafficRefillGateStatus.SKIP_DB_EMPTY
                        && retryTargetData > 0) {
                    log.debug(
                            "traffic_qos_fallback_on_gate_skip_db_empty poolType={} balanceKey={} residual={}",
                            poolType,
                            balanceKey,
                            retryTargetData
                    );
                    TrafficLuaExecutionResult qosFallbackResult = retrySharedWithQosFallback(
                            payload,
                            balanceKey,
                            retryTargetData,
                            context,
                            whitelistBypassFlag,
                            retriedResult
                    );
                    return mergeSharedDbNoopQosFallbackResult(
                            normalizedRequestedDataBytes,
                            firstDeductedAmount,
                            retriedResult,
                            qosFallbackResult
                    );
                }
                return retriedResult;
            }

            // [Step 2] 리필 권한(분산 락) 확인 및 유지
            boolean lockOwned = refreshRefillLockHeartbeat(
                    lockKey,
                    payload.getTraceId(),
                    "before_db_claim"
            );

            if (!lockOwned) {
                // 리필 진행 도중 락을 유실한 경우 안전을 위해 중단합니다.
                log.debug(
                        "traffic_refill_lock_not_owned poolType={} lockKey={}",
                        poolType,
                        lockKey
                );
                trafficRefillMetrics.increment(poolType.name(), "lock_not_owned");
                return retriedResult;
            }

            Long outboxRecordId = null;
            String outboxRefillUuid = null;
            try {
                // [Step 3] DB 원본 잔량 차감 (Outbox 기록 포함)
                String refillUuid = UUID.randomUUID().toString();
                TrafficDbRefillClaimResult claimResult = claimRefillAmountFromDbWithRetry(
                        poolType,
                        payload,
                        targetMonth,
                        requestedRefillUnit,
                        refillUuid
                );
                long dbRemainingBefore = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingBefore());
                long actualRefillAmount = normalizeNonNegative(claimResult == null ? null : claimResult.getActualRefillAmount());
                long dbRemainingAfter = normalizeNonNegative(claimResult == null ? null : claimResult.getDbRemainingAfter());
                outboxRecordId = claimResult == null ? null : claimResult.getOutboxRecordId();
                outboxRefillUuid = claimResult == null ? null : claimResult.getRefillUuid();

                if (actualRefillAmount <= 0) {
                    // DB에도 더 이상 잔량이 없는 경우(No-Op) 성공 처리 후 반환합니다.
                    markOutboxSuccessIfPresent(outboxRecordId);
                    // claim 결과상 DB가 실제 고갈된 경우에만 is_empty=1을 기록해 false positive를 방지한다.
                    markDbEmptyFlagOnDbNoop(balanceKey, dbRemainingAfter, poolType, payload);
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
                    if (poolType == TrafficPoolType.SHARED) {
                        // 공유풀 DB 리필 시도 이후에도 부족하면 마지막으로 QOS fallback을 1회 허용한다.
                        TrafficLuaExecutionResult qosFallbackResult = retrySharedWithQosFallback(
                                payload,
                                balanceKey,
                                retryTargetData,
                                context,
                                whitelistBypassFlag,
                                retriedResult
                        );
                        // DB 리필이 실제로 적용되지 않은 경우, 초기 shared 차감분과 QOS 차감분을 함께 합산해야 한다.
                        return mergeSharedDbNoopQosFallbackResult(
                                normalizedRequestedDataBytes,
                                firstDeductedAmount,
                                retriedResult,
                                qosFallbackResult
                        );
                    }
                    return retriedResult;
                }

                // 재차감 Lua 호출 직전 락 상태를 한 번 더 확인해 claim 이후 경합 구간을 줄입니다.
                boolean lockStillOwned = refreshRefillLockHeartbeat(
                        lockKey,
                        payload.getTraceId(),
                        "before_refill_retry_deduct"
                );
                if (!lockStillOwned) {
                    // 락 유실 시 Redis 반영을 중단하고 기록된 Outbox 레코드의 상태를 보정(보상처리)합니다.
                    log.warn(
                            "traffic_refill_lock_lost_before_deduct poolType={} lockKey={}",
                            poolType,
                            lockKey
                    );
                    markOutboxFailIfPresent(outboxRecordId);
                    trafficRefillOutboxSupportService.compensateRefillOnce(
                            outboxRecordId,
                            poolType,
                            payload,
                            actualRefillAmount
                    );
                    trafficRefillMetrics.increment(poolType.name(), "lock_lost");
                    return retriedResult;
                }

                String normalizedRefillUuid = (outboxRefillUuid == null || outboxRefillUuid.isBlank())
                        ? refillUuid
                        : outboxRefillUuid;
                RefillLuaArguments refillLuaArguments = RefillLuaArguments.of(
                        actualRefillAmount,
                        normalizedRefillUuid,
                        trafficRefillOutboxSupportService.resolveIdempotencyKey(normalizedRefillUuid),
                        trafficRefillOutboxSupportService.refillIdempotencyTtlSeconds(),
                        dbRemainingAfter <= 0
                );

                log.info(
                        "traffic_refill_claimed poolType={} balanceKey={} requestedRefill={} threshold={} delta={} bucketCount={} source={} dbBefore={} actualRefill={} dbAfter={}",
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

                // [Step 4] 리필 반영 + 재차감을 재차감 Lua 1회 호출로 수행합니다.
                TrafficLuaExecutionResult refillRetryResult = executeDeduct(
                        poolType,
                        payload,
                        balanceKey,
                        retryTargetData,
                        context,
                        whitelistBypassFlag,
                        TrafficFailureStage.REFILL,
                        refillLuaArguments
                );
                markOutboxSuccessIfPresent(outboxRecordId);
                trafficRefillOutboxSupportService.clearIdempotency(normalizedRefillUuid);
                trafficRefillMetrics.increment(poolType.name(), "refill_applied");

                // 초기 차감에서 부분 성공한 양과 리필 후 추가 성공한 양을 합산하여 최종 결과를 도출합니다.
                if (poolType == TrafficPoolType.SHARED && refillRetryResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
                    // 공유풀 DB 리필 이후에도 NO_BALANCE면 QOS fallback을 마지막으로 1회 시도한다.
                    long sharedRemainingData = clampRemaining(retryTargetData - normalizeNonNegative(refillRetryResult.getAnswer()));
                    if (sharedRemainingData > 0) {
                        TrafficLuaExecutionResult qosFallbackResult = retrySharedWithQosFallback(
                                payload,
                                balanceKey,
                                sharedRemainingData,
                                context,
                                whitelistBypassFlag,
                                refillRetryResult
                        );
                        return mergeSharedQosFallbackResult(
                                normalizedRequestedDataBytes,
                                firstDeductedAmount,
                                refillRetryResult,
                                qosFallbackResult
                        );
                    }
                }
                retriedResult = mergeRefillRetryResult(
                        normalizedRequestedDataBytes,
                        firstDeductedAmount,
                        refillRetryResult
                );
                return retriedResult;
            } catch (ApplicationException | DataAccessException | IllegalStateException e) {
                markOutboxFailIfPresent(outboxRecordId);
                RuntimeException unwrapped = trafficRefillOutboxSupportService.unwrapRuntimeException(e);
                String metricKey;
                if (trafficRedisFailureClassifier.isTimeoutFailure(unwrapped)) {
                    metricKey = "redis_timeout";
                } else if (trafficRedisFailureClassifier.isConnectionFailure(unwrapped)) {
                    metricKey = "redis_error";
                } else {
                    metricKey = "db_error";
                }
                log.error(
                        "traffic_refill_flow_failed poolType={} balanceKey={} traceId={} outboxId={} uuid={}",
                        poolType,
                        balanceKey,
                        payload.getTraceId(),
                        outboxRecordId,
                        outboxRefillUuid,
                        e
                );
                trafficRefillMetrics.increment(poolType.name(), metricKey);
                if (trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrapped)) {
                    throw unwrapped;
                }
                return retriedResult;
            } finally {
                // 어떤 경우든 리필 시퀀스가 종료되면 획득했던 리필 락을 해제합니다.
                trafficLuaScriptInfraService.executeLockRelease(lockKey, payload.getTraceId());
            }
        }

        return retriedResult;
    }

    /**
     * DB claim이 No-Op일 때 DB 고갈 상태를 Redis is_empty 플래그에 반영합니다.
     *
     * <p>dbRemainingAfter가 0 이하로 확정된 경우에만 is_empty=1을 기록하고,
     * Redis 쓰기 실패는 사용자 요청 실패로 승격하지 않기 위해 로그만 남기고 계속 진행합니다.
     */
    private void markDbEmptyFlagOnDbNoop(
            String balanceKey,
            long dbRemainingAfter,
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload
    ) {
        if (dbRemainingAfter > 0) {
            return;
        }

        try {
            trafficQuotaCacheService.writeDbEmptyFlag(balanceKey, true);
        } catch (ApplicationException | DataAccessException | IllegalStateException e) {
            log.warn(
                    "traffic_refill_db_noop_empty_flag_write_failed poolType={} balanceKey={} traceId={} dbRemainingAfter={}",
                    poolType,
                    balanceKey,
                    payload == null ? null : payload.getTraceId(),
                    dbRemainingAfter,
                    e
            );
        }
    }

    /**
     * 초기 차감량과 리필 후 재차감량을 합쳐 최종 결과를 만듭니다.
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
     * 공유풀 DB 리필 이후에도 부족한 경우에만 QOS fallback을 허용해 재시도합니다.
     */
    private TrafficLuaExecutionResult retrySharedWithQosFallback(
            TrafficPayloadReqDto payload,
            String balanceKey,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            int whitelistBypassFlag,
            TrafficLuaExecutionResult defaultResult
    ) {
        if (requestedDataBytes <= 0) {
            return defaultResult;
        }

        return executeDeduct(
                TrafficPoolType.SHARED,
                payload,
                balanceKey,
                requestedDataBytes,
                context,
                whitelistBypassFlag,
                TrafficFailureStage.REFILL,
                RefillLuaArguments.withQosFallback()
        );
    }

    /**
     * 공유풀 QOS 재시도 결과를 리필 합산 결과에 반영합니다.
     * QOS 재시도에서 실제 차감량이 없거나 NO_BALANCE면 기존 결과를 그대로 유지합니다.
     */
    private TrafficLuaExecutionResult mergeSharedQosFallbackResult(
            long requestedDataBytes,
            long firstDeductedAmount,
            TrafficLuaExecutionResult refillRetryResult,
            TrafficLuaExecutionResult qosRetryResult
    ) {
        if (qosRetryResult == null) {
            return mergeRefillRetryResult(requestedDataBytes, firstDeductedAmount, refillRetryResult);
        }

        long qosDeductedAmount = normalizeNonNegative(qosRetryResult.getAnswer());
        if (qosDeductedAmount <= 0 || qosRetryResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
            return mergeRefillRetryResult(requestedDataBytes, firstDeductedAmount, refillRetryResult);
        }

        long refillDeductedAmount = normalizeNonNegative(refillRetryResult == null ? null : refillRetryResult.getAnswer());
        long mergedDeductedAmount = clampToMax(
                requestedDataBytes,
                safeAdd(firstDeductedAmount, safeAdd(refillDeductedAmount, qosDeductedAmount))
        );
        return TrafficLuaExecutionResult.builder()
                .answer(mergedDeductedAmount)
                .status(qosRetryResult.getStatus())
                .build();
    }

    /**
     * 공유풀 DB No-Op 이후 QOS fallback 결과를 초기 shared 차감분과 합산합니다.
     * QOS fallback이 실패하면 기존 초기 결과를 그대로 유지합니다.
     */
    private TrafficLuaExecutionResult mergeSharedDbNoopQosFallbackResult(
            long requestedDataBytes,
            long firstDeductedAmount,
            TrafficLuaExecutionResult defaultResult,
            TrafficLuaExecutionResult qosRetryResult
    ) {
        if (qosRetryResult == null) {
            return defaultResult;
        }

        long qosDeductedAmount = normalizeNonNegative(qosRetryResult.getAnswer());
        if (qosDeductedAmount <= 0 || qosRetryResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
            return defaultResult;
        }

        long mergedDeductedAmount = clampToMax(
                requestedDataBytes,
                safeAdd(firstDeductedAmount, qosDeductedAmount)
        );
        return TrafficLuaExecutionResult.builder()
                .answer(mergedDeductedAmount)
                .status(qosRetryResult.getStatus())
                .build();
    }

    /**
     * 풀 유형에 맞는 Lua 차감 스크립트를 실행합니다.
     * Redis 연결 장애/타임아웃은 설정된 횟수만큼 재시도 후 상위로 재전파합니다.
     */
    private TrafficLuaExecutionResult executeDeduct(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            String balanceKey,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            int whitelistBypassFlag,
            TrafficFailureStage failureStage,
            RefillLuaArguments refillLuaArguments
    ) {
        int maxRetryCount = Math.max(0, redisRetryMaxAttempts);
        int totalAttemptCount = 1 + maxRetryCount;
        RuntimeException lastException = null;
        String traceId = resolveTraceId(context, payload);

        // "초기 1회 시도 -> 실패 시 대기 -> 다음 재시도" 순서를 보장합니다.
        for (int attempt = 1; attempt <= totalAttemptCount; attempt++) {
            try {
                // 실제 Lua 스크립트를 호출하여 Redis 차감을 수행합니다.
                return executeDeductLua(
                        poolType,
                        payload,
                        balanceKey,
                        requestedDataBytes,
                        whitelistBypassFlag,
                        refillLuaArguments == null ? RefillLuaArguments.none() : refillLuaArguments
                );
            } catch (ApplicationException | DataAccessException redisException) {
                // Redis 예외 발생 시 재시도 가능 여부(Timeout, Connection failure)를 확인합니다.
                RuntimeException unwrappedException = trafficRefillOutboxSupportService.unwrapRuntimeException(redisException);
                boolean retryable = trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrappedException);
                if (!retryable) {
                    TrafficStageFailureException stageFailure =
                            TrafficStageFailureException.nonRetryableFailure(failureStage, redisException);
                    log.error(
                            "{} traceId={} poolType={} requestedData={}",
                            failureStage.nonRetryableFailureLogKey(),
                            traceId,
                            poolType,
                            requestedDataBytes,
                            stageFailure
                    );
                    throw stageFailure; // 재시도 불가능한 에러(WRONGTYPE 등)는 즉시 중단합니다.
                }

                lastException = unwrappedException;
                // 메트릭 및 중복 제거 서비스에 재시도 상태를 기록합니다.
                trafficInFlightDedupeService.markRedisRetry(traceId, attempt);
                trafficDeductFallbackMetrics.incrementRedisRetry(
                        poolType.name(),
                        attempt,
                        trafficRedisFailureClassifier.isTimeoutFailure(unwrappedException) ? "timeout" : "connection"
                );

                if (attempt <= maxRetryCount) {
                    // 현재 시도 실패 직후 backoff 만큼 대기한 뒤 다음 시도로 넘어갑니다.
                    sleepRedisRetryBackoff(attempt);
                }
            }
        }

        // 현재 단계 retryable 예외는 정합성 우선 규칙에 따라 DB fallback으로 전환하지 않고 상위로 재전파한다.
        log.warn(
                "{} traceId={} poolType={} requestedData={} fallback=disabled reason={}",
                failureStage.retryExhaustedLogKey(),
                traceId,
                poolType,
                requestedDataBytes,
                failureStage.stageKey() + "_stage_retry_exhausted",
                lastException
        );
        throw TrafficStageFailureException.retryExhausted(failureStage, lastException);
    }

    /**
     * 실제 Redis Lua 차감 스크립트를 호출하기 위해 필요한 모든 키와 인자를 준비합니다.
     * 현재 시간 기준의 만료 시간 산출 및 다양한 정책 키(Policy Keys)를 매핑합니다.
     */
    private TrafficLuaExecutionResult executeDeductLua(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            String balanceKey,
            long requestedDataBytes,
            int whitelistBypassFlag,
            RefillLuaArguments refillLuaArguments
    ) {
        // 현재 시간 및 만료 시점(일일/월간)을 계산합니다.
        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());
        LocalDate targetDate = now.toLocalDate();
        YearMonth targetUsageMonth = YearMonth.from(now);
        long nowEpochSecond = now.atZone(trafficRedisRuntimePolicy.zoneId()).toEpochSecond();
        int dayNum = now.getDayOfWeek().getValue() % 7;
        int secOfDay = now.toLocalTime().toSecondOfDay();
        long dailyExpireAt = trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(targetDate);
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetUsageMonth);

        // 검증 및 차감에 필요한 Redis 정책 키들을 생성합니다.
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
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(payload.getTraceId());

        return switch (poolType) {
            case INDIVIDUAL -> {
                // 개인풀의 경우 속도 제어를 위한 속도 버킷 키를 포함하여 스크립트를 실행합니다.
                String speedBucketKey = trafficRedisKeyFactory.speedBucketIndividualAppKey(
                        payload.getLineId(),
                        payload.getAppId(),
                        nowEpochSecond
                );
                List<String> keys = List.of(
                        balanceKey, policyRepeatKey, policyImmediateKey, policyLineLimitDailyKey,
                        policyAppDataKey, policyAppSpeedKey, policyAppWhitelistKey, appWhitelistKey,
                        immediatelyBlockEndKey, repeatBlockKey, dailyTotalLimitKey, dailyTotalUsageKey,
                        appDataDailyLimitKey, dailyAppUsageKey, appSpeedLimitKey, speedBucketKey,
                        refillLuaArguments.refillIdempotencyKey(), dedupeKey
                );
                List<String> args = List.of(
                        String.valueOf(requestedDataBytes), String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum), String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond), String.valueOf(dailyExpireAt),
                        String.valueOf(whitelistBypassFlag),
                        String.valueOf(refillLuaArguments.refillAmount()),
                        refillLuaArguments.refillUuid(),
                        String.valueOf(refillLuaArguments.refillIdempotencyTtlSeconds()),
                        refillLuaArguments.dbEmptyFlag(),
                        String.valueOf(refillLuaArguments.allowQosFallback()),
                        String.valueOf(normalizeNonNegative(payload.getApiTotalData()))
                );
                yield trafficLuaScriptInfraService.executeDeductIndividual(keys, args);
            }
            case SHARED -> {
                // 공유풀의 경우 월간 공유 한도 키를 추가로 포함하여 스크립트를 실행합니다.
                String monthlySharedLimitKey = trafficRedisKeyFactory.monthlySharedLimitKey(payload.getLineId());
                String monthlySharedUsageKey = trafficRedisKeyFactory.monthlySharedUsageKey(payload.getLineId(), targetUsageMonth);
                // 앱 속도 제한은 풀 유형과 무관하게 회선(line) + 앱 단위로 동일하게 적용합니다.
                String speedBucketKey = trafficRedisKeyFactory.speedBucketIndividualAppKey(
                        payload.getLineId(),
                        payload.getAppId(),
                        nowEpochSecond
                );
                String individualRemainingKey = trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetUsageMonth);
                List<String> keys = List.of(
                        balanceKey, policyRepeatKey, policyImmediateKey, policyLineLimitSharedKey,
                        policyLineLimitDailyKey, policyAppDataKey, policyAppSpeedKey, policyAppWhitelistKey,
                        appWhitelistKey, immediatelyBlockEndKey, repeatBlockKey, dailyTotalLimitKey,
                        dailyTotalUsageKey, monthlySharedLimitKey, monthlySharedUsageKey,
                        appDataDailyLimitKey, dailyAppUsageKey, appSpeedLimitKey, speedBucketKey, individualRemainingKey,
                        refillLuaArguments.refillIdempotencyKey(), dedupeKey
                );
                List<String> args = List.of(
                        String.valueOf(requestedDataBytes), String.valueOf(payload.getAppId()),
                        String.valueOf(dayNum), String.valueOf(secOfDay),
                        String.valueOf(nowEpochSecond), String.valueOf(dailyExpireAt), String.valueOf(monthlyExpireAt),
                        String.valueOf(whitelistBypassFlag),
                        String.valueOf(refillLuaArguments.refillAmount()),
                        refillLuaArguments.refillUuid(),
                        String.valueOf(refillLuaArguments.refillIdempotencyTtlSeconds()),
                        refillLuaArguments.dbEmptyFlag(),
                        String.valueOf(refillLuaArguments.allowQosFallback()),
                        String.valueOf(normalizeNonNegative(payload.getApiTotalData()))
                );
                yield trafficLuaScriptInfraService.executeDeductShared(keys, args);
            }
        };
    }

    /**
     * 재차감 Lua 호출 시 리필 적용 파라미터를 전달하기 위한 값 객체입니다.
     */
    private record RefillLuaArguments(
            long refillAmount,
            String refillUuid,
            String refillIdempotencyKey,
            long refillIdempotencyTtlSeconds,
            String dbEmptyFlag,
            int allowQosFallback
    ) {
        private static final String UNUSED_KEY = "__unused_refill_idempotency__";

        private static RefillLuaArguments none() {
            return new RefillLuaArguments(0L, "", UNUSED_KEY, 0L, "0", 0);
        }

        private static RefillLuaArguments withQosFallback() {
            return new RefillLuaArguments(0L, "", UNUSED_KEY, 0L, "0", 1);
        }

        private static RefillLuaArguments of(
                long refillAmount,
                String refillUuid,
                String refillIdempotencyKey,
                long refillIdempotencyTtlSeconds,
                boolean dbEmpty
        ) {
            String normalizedUuid = refillUuid == null ? "" : refillUuid;
            String normalizedKey = (refillIdempotencyKey == null || refillIdempotencyKey.isBlank())
                    ? UNUSED_KEY
                    : refillIdempotencyKey;
            return new RefillLuaArguments(
                    Math.max(0L, refillAmount),
                    normalizedUuid,
                    normalizedKey,
                    Math.max(0L, refillIdempotencyTtlSeconds),
                    dbEmpty ? "1" : "0",
                    0
            );
        }
    }

    /**
     * 지수 backoff(50/100/200ms)로 Redis 재시도 간격을 제어합니다.
     */
    private void sleepRedisRetryBackoff(int retryAttempt) {
        long delayMs = TrafficRetryBackoffSupport.resolveDelayMs(redisRetryBackoffMs, retryAttempt);
        if (delayMs <= 0L) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_deduct_redis_retry_sleep_interrupted retryAttempt={} delayMs={}",
                    retryAttempt,
                    delayMs
            );
        }
    }

    private String resolveTraceId(TrafficDeductExecutionContext context, TrafficPayloadReqDto payload) {
        if (context != null && context.getTraceId() != null && !context.getTraceId().isBlank()) {
            return context.getTraceId();
        }
        return payload == null ? null : payload.getTraceId();
    }

    /**
     *
     * @return remaining_indiv/shared_amount ??
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
    }

    /**
     *
     * @return indiv/shared refill lock ??
     */
    private String resolveLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivRefillLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedRefillLockKey(payload.getFamilyId());
        };
    }

    /**
     *
     * @return indiv/shared hydrate lock ??
     */
    private String resolveHydrateLockKey(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.indivHydrateLockKey(payload.getLineId());
            case SHARED -> trafficRedisKeyFactory.sharedHydrateLockKey(payload.getFamilyId());
        };
    }

    /**
     * 외부 인프라 접근 직전에 lock heartbeat를 실행해 소유권을 재확인하고 TTL을 갱신합니다.
     */
    private boolean refreshRefillLockHeartbeat(String lockKey, String traceId, String boundary) {
        boolean lockOwned = trafficLuaScriptInfraService.executeLockHeartbeat(
                lockKey,
                traceId,
                TrafficRedisRuntimePolicy.LOCK_TTL_MS
        );
        if (!lockOwned) {
            log.debug(
                    "traffic_refill_lock_heartbeat_not_owned lockKey={} boundary={}",
                    lockKey,
                    boundary
            );
        }
        return lockOwned;
    }

    /**
     * hydrate 처리를 위한 분산 락 획득을 시도합니다.
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
     * hydrate 재시도 전 지수 백오프 대기를 수행합니다.
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
     * 이벤트가 속한 사용 월을 계산합니다.
     */
    private YearMonth resolveTargetMonth(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        }

        return YearMonth.from(Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()));
    }

    /**
     * 풀 유형에 필요한 payload 필수값이 모두 존재하는지 확인합니다.
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

        return switch (poolType) {
            case INDIVIDUAL -> true;
            case SHARED -> payload.getFamilyId() != null && payload.getFamilyId() > 0;
        };
    }

    /**
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
     * Long 값을 0 이상의 값으로 보정합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * Integer 값을 0 이상의 값으로 보정합니다.
     */
    private int normalizeNonNegativeInt(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value;
    }

    /**
     * 남은 차감 대상 값을 0 이상으로 보정합니다.
     */
    private long clampRemaining(long value) {
        if (value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * 값이 최대 허용치를 넘지 않도록 제한합니다.
     */
    private long clampToMax(long maxValue, long value) {
        long normalizedMaxValue = Math.max(0L, maxValue);
        if (value <= 0) {
            return 0L;
        }
        return Math.min(normalizedMaxValue, value);
    }

    /**
     * 오버플로 없이 두 값을 더합니다.
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
     * outbox 레코드가 존재할 때 SUCCESS로 전이합니다.
     */
    private void markOutboxSuccessIfPresent(Long outboxRecordId) {
        if (outboxRecordId == null || outboxRecordId <= 0) {
            return;
        }
        redisOutboxRecordService.markSuccess(outboxRecordId);
    }

    /**
     * outbox 레코드가 존재할 때 FAIL로 전이합니다.
     */
    private void markOutboxFailIfPresent(Long outboxRecordId) {
        if (outboxRecordId == null || outboxRecordId <= 0) {
            return;
        }
        redisOutboxRecordService.markFail(outboxRecordId);
    }

    /**
     * DB에서 리필 대상 금액을 차감하여 할당받습니다. 
     * DB 데드락이나 락 대기 시간 초과 발생 시 지정된 횟수만큼 재시도합니다.
     * 
     * [상세로직]
     * 1. DB 예외 발생 시 isRetryableDbException()을 통해 재시도 가능 여부를 예외 타입으로 판단.
     * 2. 재시도 가능 시 DB_RETRY_MAX(3회)만큼 지수 백오프(50/100/200ms) 후 재시도.
     * 3. 최종 실패 시 상위로 예외를 전파하여 Outbox에 FAIL 기록을 유도.
     */
    private TrafficDbRefillClaimResult claimRefillAmountFromDbWithRetry(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            long requestedRefillAmount,
            String refillUuid
    ) {
        DataAccessException lastException = null;
        // DB claim도 동일하게 "초기 1회 시도 -> 실패 시 대기 -> 재시도" 순서를 따릅니다.
        for (int retryCount = 0; retryCount <= DB_RETRY_MAX; retryCount++) {
            try {
                // DB에서 실잔량을 차값하고 Outbox를 생성하는 핵심 로직을 호출합니다.
                return trafficQuotaSourcePort.claimRefillAmountFromDb(
                        poolType,
                        payload,
                        targetMonth,
                        requestedRefillAmount,
                        refillUuid
                );
            } catch (DataAccessException e) {
                lastException = e;
                // DB 락 경합/타임아웃 계열 예외인지 타입 기준으로 재시도 가능 여부를 판단합니다.
                boolean retryable = isRetryableDbException(e);
                if (!retryable || retryCount >= DB_RETRY_MAX) {
                    throw e; // 재시도 대상이 아니거나 횟수 초과 시 즉시 중단합니다.
                }

                log.warn(
                        "traffic_refill_db_retry poolType={} traceId={} retry={}/{}",
                        poolType,
                        payload.getTraceId(),
                        retryCount + 1,
                        DB_RETRY_MAX
                );
                sleepDbRetryBackoff(retryCount + 1); // 실패 후 대기한 다음 다시 시도합니다.
            }
        }

        throw lastException == null
                ? new IllegalStateException("traffic_refill_db_retry_exhausted")
                : lastException;
    }

    /**
     * DB 예외가 재시도 가능한 종류인지 판별합니다.
     */
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

    /**
     * DB 재시도 전 backoff 시간만큼 대기합니다.
     */
    private void sleepDbRetryBackoff(int retryAttempt) {
        long delayMs = TrafficRetryBackoffSupport.resolveDelayMs(redisRetryBackoffMs, retryAttempt);
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_refill_db_retry_sleep_interrupted retryAttempt={} delayMs={}",
                    retryAttempt,
                    delayMs
            );
        }
    }
}
