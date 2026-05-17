package com.pooli.traffic.service.decision;

import java.util.List;
import java.time.LocalDateTime;

import com.pooli.common.exception.ApplicationException;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficPolicyCheckLayerResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 차단성 정책 검증 전용 레이어입니다.
 *
 * <p>책임 범위:
 * 1) 차단성 정책 검증 Lua 실행
 * 2) GLOBAL_POLICY_HYDRATE 보정 재시도
 * 3) retryable 인프라 예외의 pending/reclaim 대상 판정
 *
 * <p>비포함 범위:
 * - line 정책 ensureLoaded 호출
 * - retryable 실패 이후의 stream pending/reclaim 처리
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficPolicyCheckLayerService {

    private static final int HYDRATE_RETRY_MAX = 5;
    private static final long POLICY_REPEAT_BLOCK_ID = 1L;
    private static final long POLICY_IMMEDIATE_BLOCK_ID = 2L;
    private static final long POLICY_APP_WHITELIST_ID = 7L;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long redisRetryBackoffMs;

    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficPolicyBootstrapService trafficPolicyBootstrapService;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    /**
     * 차단성 정책(즉시/반복/화이트리스트)을 검증하고 fallback 판정 계약을 반환합니다.
     */
    public TrafficPolicyCheckLayerResult evaluate(TrafficPayloadReqDto payload) {
        TrafficFailureStage failureStage = TrafficFailureStage.POLICY_CHECK;
        try {
            TrafficLuaExecutionResult luaResult = executePolicyCheckWithGlobalRecovery(payload);
            return TrafficPolicyCheckLayerResult.fromLuaResult(luaResult);
        } catch (ApplicationException | DataAccessException e) {
            if (trafficRedisFailureClassifier.isRetryableInfrastructureFailure(e)) {
                TrafficStageFailureException stageFailure =
                        TrafficStageFailureException.retryableFailure(failureStage, e);
                log.warn(
                        "{} traceId={} failureCause={}",
                        failureStage.retryableFailureLogKey(),
                        payload == null ? null : payload.getTraceId(),
                        TrafficPolicyCheckFailureCause.POLICY_CHECK_RETRYABLE,
                        stageFailure
                );
                return TrafficPolicyCheckLayerResult.retryableFailure(
                        TrafficPolicyCheckFailureCause.POLICY_CHECK_RETRYABLE,
                        stageFailure
                );
            }
            TrafficStageFailureException stageFailure =
                    TrafficStageFailureException.nonRetryableFailure(failureStage, e);
            log.error(
                    "{} traceId={} failureCause={}",
                    failureStage.nonRetryableFailureLogKey(),
                    payload == null ? null : payload.getTraceId(),
                    TrafficPolicyCheckFailureCause.POLICY_CHECK_NON_RETRYABLE,
                    stageFailure
            );
            throw stageFailure;
        }
    }

    /**
     * 정책 검증 Lua를 실행하고, GLOBAL_POLICY_HYDRATE 상태면 전역 정책 스냅샷 보정 후 재시도합니다.
     */
    private TrafficLuaExecutionResult executePolicyCheckWithGlobalRecovery(TrafficPayloadReqDto payload) {
        TrafficLuaExecutionResult policyCheckResult = executePolicyCheckLua(payload);
        if (policyCheckResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
            return policyCheckResult;
        }

        TrafficLuaExecutionResult retriedResult = policyCheckResult;
        for (int retry = 0; retry < HYDRATE_RETRY_MAX; retry++) {
            try {
                trafficPolicyBootstrapService.hydrateOnDemand();
            } catch (ApplicationException | DataAccessException e) {
                log.error(
                        "traffic_policy_check_global_hydrate_failed traceId={} retry={}",
                        payload == null ? null : payload.getTraceId(),
                        retry + 1,
                        e
                );
            }

            retriedResult = executePolicyCheckLua(payload);
            if (retriedResult.getStatus() != TrafficLuaStatus.GLOBAL_POLICY_HYDRATE) {
                return retriedResult;
            }
            sleepHydrateRetryBackoff(retry + 1);
        }

        log.error(
                "traffic_policy_check_global_hydrate_retry_exhausted traceId={}",
                payload == null ? null : payload.getTraceId()
        );
        return retriedResult;
    }

    /**
     * 차단성 정책 검증 Lua를 실행합니다.
     */
    private TrafficLuaExecutionResult executePolicyCheckLua(TrafficPayloadReqDto payload) {
        long lineId = payload == null || payload.getLineId() == null ? 0L : payload.getLineId();
        int appId = payload == null || payload.getAppId() == null ? -1 : payload.getAppId();

        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());
        long nowEpochSecond = now.atZone(trafficRedisRuntimePolicy.zoneId()).toEpochSecond();
        int dayNum = now.getDayOfWeek().getValue() % 7;
        int secOfDay = now.toLocalTime().toSecondOfDay();

        String policyRepeatKey = trafficRedisKeyFactory.policyKey(POLICY_REPEAT_BLOCK_ID);
        String policyImmediateKey = trafficRedisKeyFactory.policyKey(POLICY_IMMEDIATE_BLOCK_ID);
        String policyAppWhitelistKey = trafficRedisKeyFactory.policyKey(POLICY_APP_WHITELIST_ID);
        String appWhitelistKey = trafficRedisKeyFactory.appWhitelistKey(lineId);
        String immediatelyBlockEndKey = trafficRedisKeyFactory.immediatelyBlockEndKey(lineId);
        String repeatBlockKey = trafficRedisKeyFactory.repeatBlockKey(lineId);

        List<String> keys = List.of(
                policyRepeatKey,
                policyImmediateKey,
                policyAppWhitelistKey,
                appWhitelistKey,
                immediatelyBlockEndKey,
                repeatBlockKey
        );
        List<String> args = List.of(
                String.valueOf(appId),
                String.valueOf(dayNum),
                String.valueOf(secOfDay),
                String.valueOf(nowEpochSecond)
        );

        return trafficLuaScriptInfraService.executeBlockPolicyCheck(keys, args);
    }

    /**
     * hydrate 재시도 전 지수 백오프 대기를 수행합니다.
     */
    private void sleepHydrateRetryBackoff(int retryAttempt) {
        long waitMs = TrafficRetryBackoffSupport.resolveDelayMs(redisRetryBackoffMs, retryAttempt);
        if (waitMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "traffic_policy_check_retry_sleep_interrupted retryAttempt={} delayMs={}",
                    retryAttempt,
                    waitMs
            );
        }
    }
}
