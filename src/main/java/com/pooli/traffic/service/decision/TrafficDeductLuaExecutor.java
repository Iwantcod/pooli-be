package com.pooli.traffic.service.decision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 통합 차감 Lua 실행을 전담합니다.
 *
 * <p>개인풀/공유풀/QoS 차감에 필요한 Redis key와 Lua argument를 구성하고,
 * Redis 인프라성 실패에는 동일 Lua 재시도 정책을 적용합니다. Hydrate 자체는 수행하지 않습니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductLuaExecutor {

    private static final long POLICY_LINE_LIMIT_SHARED_ID = 3L;
    private static final long POLICY_LINE_LIMIT_DAILY_ID = 4L;
    private static final long POLICY_APP_DATA_ID = 5L;
    private static final long POLICY_APP_SPEED_ID = 6L;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long redisRetryBackoffMs;

    @Value("${app.traffic.deduct.redis-retry.max-attempts:3}")
    private int redisRetryMaxAttempts;

    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    /**
     * 통합 Lua 차감을 실행하고 retryable Redis 장애에만 재시도를 적용합니다.
     */
    public TrafficLuaDeductExecutionResult executeUnifiedWithRetry(
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            TrafficFailureStage failureStage
    ) {
        // 통합 Lua는 개인/공유 잔량과 QoS 한도를 모두 참조하므로 두 풀 식별자가 모두 필요합니다.
        if (!isPayloadValidForUnifiedDeduct(payload)) {
            return unifiedErrorResult();
        }

        // 월별 잔량 key는 이벤트 발생 시각(enqueuedAt)을 기준으로 고정합니다.
        YearMonth targetMonth = resolveTargetMonth(payload);
        String individualBalanceKey = resolveBalanceKey(TrafficPoolType.INDIVIDUAL, payload, targetMonth);
        String sharedBalanceKey = resolveBalanceKey(TrafficPoolType.SHARED, payload, targetMonth);
        int whitelistBypassFlag = resolveWhitelistBypassFlag(context);

        return executeUnifiedDeduct(
                payload,
                individualBalanceKey,
                sharedBalanceKey,
                requestedDataBytes,
                whitelistBypassFlag,
                failureStage
        );
    }

    /**
     * Redis 인프라성 실패에는 재시도 정책을 적용하고, 실패가 소진되면 DB fallback 없이 단계 실패로 종료합니다.
     */
    private TrafficLuaDeductExecutionResult executeUnifiedDeduct(
            TrafficPayloadReqDto payload,
            String individualBalanceKey,
            String sharedBalanceKey,
            long requestedDataBytes,
            int whitelistBypassFlag,
            TrafficFailureStage failureStage
    ) {
        // Redis retry 설정은 "최초 시도 + 재시도 횟수"로 계산합니다.
        String traceId = payload == null ? null : payload.getTraceId();
        RuntimeException lastRetryableFailure = null;
        int maxAttempts = Math.max(1, redisRetryMaxAttempts + 1);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 실제 Redis Lua 호출은 key/argv 조립 메서드로 분리합니다.
                TrafficLuaDeductExecutionResult result = executeUnifiedDeductLua(
                        payload,
                        individualBalanceKey,
                        sharedBalanceKey,
                        requestedDataBytes,
                        whitelistBypassFlag
                );
                // 성공 전 retryable 실패가 있었다면 Redis retry 메트릭에만 기록합니다.
                recordRetryableFailureAttempts(TrafficPoolType.INDIVIDUAL, attempt - 1, lastRetryableFailure);
                return result;
            } catch (ApplicationException | DataAccessException exception) {
                // non-retryable 예외는 재시도하지 않고 현재 failure stage로 래핑해 전파합니다.
                if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)) {
                    recordRetryableFailureAttempts(TrafficPoolType.INDIVIDUAL, attempt - 1, lastRetryableFailure);
                    TrafficStageFailureException stageFailure =
                            TrafficStageFailureException.nonRetryableFailure(failureStage, exception);
                    log.error(
                            "{} traceId={} poolType=UNIFIED requestedData={}",
                            failureStage.nonRetryableFailureLogKey(),
                            traceId,
                            requestedDataBytes,
                            stageFailure
                    );
                    throw stageFailure;
                }

                lastRetryableFailure = exception;
                if (attempt >= maxAttempts) {
                    // Redis-Only 원칙상 retry 소진 후 DB fallback 없이 단계 실패로 종료합니다.
                    recordRetryableFailureAttempts(TrafficPoolType.INDIVIDUAL, attempt, lastRetryableFailure);
                    log.warn(
                            "{} traceId={} poolType=UNIFIED requestedData={} fallback=disabled reason={}",
                            failureStage.retryExhaustedLogKey(),
                            traceId,
                            requestedDataBytes,
                            failureStage.stageKey() + "_stage_retry_exhausted",
                            lastRetryableFailure
                    );
                    throw TrafficStageFailureException.retryExhausted(failureStage, lastRetryableFailure);
                }
                // 남은 시도가 있으면 같은 Lua를 backoff 후 재실행합니다.
                sleepRedisRetryBackoff(attempt);
            }
        }

        throw TrafficStageFailureException.retryExhausted(failureStage, lastRetryableFailure);
    }

    /**
     * 통합 차감 Lua의 key/argument 계약을 구성하고 Redis 원자 구간에서 차감을 실행합니다.
     */
    private TrafficLuaDeductExecutionResult executeUnifiedDeductLua(
            TrafficPayloadReqDto payload,
            String individualBalanceKey,
            String sharedBalanceKey,
            long requestedDataBytes,
            int whitelistBypassFlag
    ) {
        // 사용량 집계 key는 enqueuedAt 기준 날짜를 사용하고, 속도 버킷만 현재 초를 사용합니다.
        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());
        LocalDate targetDate = resolveTargetDate(payload);
        YearMonth targetUsageMonth = YearMonth.from(targetDate);
        long nowEpochSecond = now.atZone(trafficRedisRuntimePolicy.zoneId()).toEpochSecond();
        long dailyExpireAt = trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(targetDate);
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetUsageMonth);

        String policyLineLimitSharedKey = trafficRedisKeyFactory.policyKey(POLICY_LINE_LIMIT_SHARED_ID);
        String policyLineLimitDailyKey = trafficRedisKeyFactory.policyKey(POLICY_LINE_LIMIT_DAILY_ID);
        String policyAppDataKey = trafficRedisKeyFactory.policyKey(POLICY_APP_DATA_ID);
        String policyAppSpeedKey = trafficRedisKeyFactory.policyKey(POLICY_APP_SPEED_ID);
        String dailyTotalLimitKey = trafficRedisKeyFactory.dailyTotalLimitKey(payload.getLineId());
        String dailyTotalUsageKey = trafficRedisKeyFactory.dailyTotalUsageKey(payload.getLineId(), targetDate);
        String monthlySharedLimitKey = trafficRedisKeyFactory.monthlySharedLimitKey(payload.getLineId());
        String monthlySharedUsageKey = trafficRedisKeyFactory.monthlySharedUsageKey(payload.getLineId(), targetUsageMonth);
        String appDataDailyLimitKey = trafficRedisKeyFactory.appDataDailyLimitKey(payload.getLineId());
        String dailyAppUsageKey = trafficRedisKeyFactory.dailyAppUsageKey(payload.getLineId(), targetDate);
        String appSpeedLimitKey = trafficRedisKeyFactory.appSpeedLimitKey(payload.getLineId());
        String speedBucketKey = trafficRedisKeyFactory.speedBucketIndividualAppKey(
                payload.getLineId(),
                payload.getAppId(),
                nowEpochSecond
        );
        String dedupeKey = trafficRedisKeyFactory.dedupeRunKey(payload.getTraceId());

        // 통합 Lua의 KEYS 순서와 ARGV 순서는 deduct_unified.lua와 1:1 계약입니다.
        List<String> keys = List.of(
                individualBalanceKey,
                sharedBalanceKey,
                policyLineLimitSharedKey,
                policyLineLimitDailyKey,
                policyAppDataKey,
                policyAppSpeedKey,
                dailyTotalLimitKey,
                dailyTotalUsageKey,
                monthlySharedLimitKey,
                monthlySharedUsageKey,
                appDataDailyLimitKey,
                dailyAppUsageKey,
                appSpeedLimitKey,
                speedBucketKey,
                dedupeKey
        );
        List<String> args = List.of(
                String.valueOf(requestedDataBytes),
                String.valueOf(payload.getAppId()),
                String.valueOf(nowEpochSecond),
                String.valueOf(dailyExpireAt),
                String.valueOf(monthlyExpireAt),
                String.valueOf(whitelistBypassFlag),
                String.valueOf(payload.getApiTotalData())
        );
        // Redis 원자 구간에서 개인/공유/QoS 처리량과 usage/dedupe 갱신을 함께 수행합니다.
        return trafficLuaScriptInfraService.executeDeductUnified(keys, args);
    }

    /**
     * 통합 차감 재시도 중 발생한 retryable 실패 횟수를 Redis retry 메트릭에 기록합니다.
     *
     * <p>in-flight dedupe의 retry_count는 메시지 reclaim 횟수 전용이므로 여기서 갱신하지 않습니다.
     */
    private void recordRetryableFailureAttempts(
            TrafficPoolType poolType,
            int failedAttemptCount,
            RuntimeException lastFailure
    ) {
        int normalizedFailedAttemptCount = Math.max(0, failedAttemptCount);
        if (normalizedFailedAttemptCount <= 0) {
            return;
        }

        String reason = trafficRedisFailureClassifier.isTimeoutFailure(lastFailure) ? "timeout" : "connection";
        for (int attempt = 1; attempt <= normalizedFailedAttemptCount; attempt++) {
            trafficDeductFallbackMetrics.incrementRedisRetry(poolType.name(), attempt, reason);
        }
    }

    /**
     * 오케스트레이터 선행 정책 검증 결과가 whitelist bypass를 허용했는지 Lua 인자로 변환합니다.
     */
    private int resolveWhitelistBypassFlag(TrafficDeductExecutionContext context) {
        if (context == null) {
            return 0;
        }
        TrafficLuaExecutionResult cachedPolicyCheckResult = context.getBlockingPolicyCheckResult();
        if (cachedPolicyCheckResult == null || cachedPolicyCheckResult.getStatus() != TrafficLuaStatus.OK) {
            return 0;
        }
        Long answer = cachedPolicyCheckResult.getAnswer();
        return answer != null && answer > 0 ? 1 : 0;
    }

    /**
     * 풀 유형과 대상 월 기준으로 통합 Lua가 사용할 월별 잔량 hash key를 생성합니다.
     */
    private String resolveBalanceKey(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(payload.getLineId(), targetMonth);
            case SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(payload.getFamilyId(), targetMonth);
        };
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
     * 일별 사용량 key 기준 날짜를 enqueuedAt 기준으로 산출하고, 값이 없으면 현재 날짜를 사용합니다.
     */
    private LocalDate resolveTargetDate(TrafficPayloadReqDto payload) {
        Long enqueuedAt = payload.getEnqueuedAt();
        if (enqueuedAt == null || enqueuedAt <= 0) {
            return LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        }
        return Instant.ofEpochMilli(enqueuedAt).atZone(trafficRedisRuntimePolicy.zoneId()).toLocalDate();
    }

    /**
     * 통합 Lua 차감에 필요한 공통 payload 필드와 풀별 owner 식별자를 검증합니다.
     */
    private boolean isPayloadValidForUnifiedDeduct(TrafficPayloadReqDto payload) {
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
        return payload.getFamilyId() != null && payload.getFamilyId() > 0;
    }

    /**
     * payload 검증 실패 시 차감량 없이 ERROR 상태를 반환하는 표준 결과를 생성합니다.
     */
    private TrafficLuaDeductExecutionResult unifiedErrorResult() {
        return TrafficLuaDeductExecutionResult.builder()
                .indivDeducted(0L)
                .sharedDeducted(0L)
                .qosDeducted(0L)
                .status(TrafficLuaStatus.ERROR)
                .build();
    }

    /**
     * Redis retry 루프의 짧은 backoff를 적용하고 interrupt 발생 시 현재 thread 상태를 복구합니다.
     */
    private void sleepRedisRetryBackoff(int retryAttempt) {
        long waitMs = TrafficRetryBackoffSupport.resolveDelayMs(redisRetryBackoffMs, retryAttempt);
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_deduct_retry_sleep_interrupted retryAttempt={} delayMs={}",
                    retryAttempt,
                    waitMs
            );
        }
    }

}
