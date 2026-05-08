package com.pooli.traffic.service.decision;

import java.time.LocalDateTime;

import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.domain.TrafficPolicyCheckLayerResult;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import com.pooli.traffic.service.outbox.TrafficRefillOutboxSupportService;
import com.pooli.traffic.service.policy.TrafficLinePolicyHydrationService;
import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import com.pooli.traffic.service.runtime.TrafficBalanceStateWriteThroughService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 트래픽 차감 이벤트 1건을 단일 사이클로 처리하는 오케스트레이션 서비스입니다.
 * 정상 Redis 경로는 단일 Lua에서 개인풀, 공유풀, QoS 순서로 차감합니다.
 * 정책 단계 retryable 장애로 DB fallback을 사용하는 잔존 경로만 개인풀 후 공유풀 보완 차감을 유지합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductOrchestratorService {

    private final TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;
    private final TrafficRecentUsageBucketService trafficRecentUsageBucketService;
    private final TrafficSharedPoolThresholdAlarmService trafficSharedPoolThresholdAlarmService;
    private final TrafficBalanceStateWriteThroughService trafficBalanceStateWriteThroughService;
    private final TrafficLinePolicyHydrationService trafficLinePolicyHydrationService;
    private final TrafficPolicyCheckLayerService trafficPolicyCheckLayerService;
    private final TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final TrafficDbDeductFallbackService trafficDbDeductFallbackService;
    private final TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

    /**
     * 이벤트 1건의 목표 데이터량(apiTotalData)을 처리하고 최종 상태를 반환합니다.
     */
    public TrafficDeductResultResDto orchestrate(TrafficPayloadReqDto payload) {
        // 요청 처리 시작 시각을 기록해 단건 처리 레이턴시를 추적할 수 있게 합니다.
        LocalDateTime startedAt = LocalDateTime.now();

        // 메시지 기준 목표 차감량(apiTotalData)과 누적 차감량 상태를 초기화합니다.
        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        long apiRemainingData = apiTotalData;
        long deductedIndividualBytes = 0L;
        long deductedSharedBytes = 0L;
        long deductedQosBytes = 0L;
        TrafficLuaStatus lastLuaStatus = null;
        // 개인/공유 차감 재시도 경로에서 traceId 단위 상태를 공유하기 위한 컨텍스트를 준비합니다.
        TrafficDeductExecutionContext deductExecutionContext = TrafficDeductExecutionContext.of(
                payload == null ? null : payload.getTraceId()
        );

        if (apiRemainingData > 0) {
            TrafficPolicyCheckLayerResult policyCheckResult = null;
            boolean useDbFallbackForDeduct = false;
            boolean preCheckEligible = canRunBlockingPolicyPreCheck(payload);

            // M4-2-d: 오케스트레이터 선행 단계에서 정책 로드/검증을 1회 수행합니다.
            if (preCheckEligible) {
                policyCheckResult = evaluateBlockingPolicyCheck(payload);
                useDbFallbackForDeduct = policyCheckResult.isFallbackEligible();
            } else {
                // 선검증 최소 필드(lineId/appId)가 없으면 기존 차감 경로로 내려 보내어
                // 어댑터의 payload 검증/오류 처리 규칙을 그대로 적용합니다.
                log.debug(
                        "traffic_policy_precheck_skipped traceId={} reason=missing_minimum_fields",
                        payload == null ? null : payload.getTraceId()
                );
            }

            // [분기 1] 정책 단계 retryable 인프라 장애로 fallbackEligible=true 이면 즉시 DB fallback을 실행합니다.
            // EM4에서 DB fallback을 제거하기 전까지 유지하는 legacy 경로이며, QoS 차감량은 항상 0으로 취급합니다.
            if (useDbFallbackForDeduct) {
                TrafficLuaExecutionResult individualResult = activateDbFallbackForPolicyCheck(
                        TrafficPoolType.INDIVIDUAL,
                        payload,
                        apiRemainingData,
                        deductExecutionContext,
                        policyCheckResult.getFailure()
                );
                lastLuaStatus = individualResult.getStatus();

                long indivDeducted = normalizeNonNegative(individualResult.getAnswer());
                deductedIndividualBytes += indivDeducted;
                apiRemainingData = clampRemaining(apiRemainingData - indivDeducted);
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, indivDeducted);

                long residualData = apiRemainingData;
                if (residualData > 0 && individualResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
                    TrafficLuaExecutionResult sharedResult = activateDbFallbackForPolicyCheck(
                            TrafficPoolType.SHARED,
                            payload,
                            residualData,
                            deductExecutionContext,
                            policyCheckResult == null ? null : policyCheckResult.getFailure()
                    );
                    lastLuaStatus = sharedResult.getStatus();

                    long sharedDeducted = normalizeNonNegative(sharedResult.getAnswer());
                    deductedSharedBytes += sharedDeducted;
                    apiRemainingData = clampRemaining(apiRemainingData - sharedDeducted);
                    trafficRecentUsageBucketService.recordUsage(TrafficPoolType.SHARED, payload, sharedDeducted);
                    if (sharedDeducted > 0) {
                        safeSyncSharedMetaConsumed(payload, sharedDeducted);
                        safeCheckAndEnqueueSharedThresholdAlarm(payload);
                    }
                }
            // [분기 2] 현재 차단 정책이 적용 중이거나, 정책 검증 자체가 실패한 상태입니다.
            // 즉시차단/반복차단/오류에서는 차감을 절대 시도하지 않고, "차감 0" 결과를 바로 반환합니다.
            // 이 반환값은 상위(StreamConsumerRunner)에서 done log 저장 후 ACK 처리로 이어집니다.
            } else if (policyCheckResult != null && policyCheckResult.getStatus() != TrafficLuaStatus.OK) {
                return buildOrchestrateResult(
                        payload,
                        apiTotalData,
                        0L,
                        0L,
                        0L,
                        apiRemainingData,
                        policyCheckResult.getStatus(),
                        startedAt
                );
            } else {
                // [분기 3] 정책 허용(또는 선검증 생략) 케이스는 개인/공유/QoS 통합 Lua를 1회 호출합니다.
                // Lua가 출처별 차감량을 반환하므로 오케스트레이터는 총 잔여량과 부가 알람만 정리합니다.
                // 기존 어댑터 내부 재검증을 우회하기 위해 오케스트레이터 검증 결과를 컨텍스트에 주입합니다.
                if (policyCheckResult != null) {
                    deductExecutionContext.cacheBlockingPolicyCheckResult(policyCheckResult.toLuaExecutionResult());
                }
                TrafficLuaDeductExecutionResult unifiedResult =
                        trafficHydrateRefillAdapterService.executeUnifiedWithRecovery(
                                payload,
                                apiRemainingData,
                                deductExecutionContext
                        );
                lastLuaStatus = unifiedResult.getStatus();

                long indivDeducted = normalizeNonNegative(unifiedResult.getIndivDeducted());
                long sharedDeducted = normalizeNonNegative(unifiedResult.getSharedDeducted());
                long qosDeducted = normalizeNonNegative(unifiedResult.getQosDeducted());
                deductedIndividualBytes += indivDeducted;
                deductedSharedBytes += sharedDeducted;
                deductedQosBytes += qosDeducted;
                apiRemainingData = clampRemaining(apiRemainingData - unifiedResult.getTotalDeducted());
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, indivDeducted);
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.SHARED, payload, sharedDeducted);
                if (sharedDeducted > 0) {
                    safeSyncSharedMetaConsumed(payload, sharedDeducted);
                    safeCheckAndEnqueueSharedThresholdAlarm(payload);
                }
            }
        }
        // 단건 처리 결과를 최종 응답 DTO로 조립합니다.
        return buildOrchestrateResult(
                payload,
                apiTotalData,
                deductedIndividualBytes,
                deductedSharedBytes,
                deductedQosBytes,
                apiRemainingData,
                lastLuaStatus,
                startedAt
        );
    }

    /**
     * 공유풀 임계치 알람은 부가 기능이므로, 실패가 핵심 차감 결과를 오염시키지 않게 보호합니다.
     */
    private void safeCheckAndEnqueueSharedThresholdAlarm(TrafficPayloadReqDto payload) {
        // family 식별자를 먼저 추출해 로깅/알람 enqueue에 동일하게 사용합니다.
        Long familyId = payload == null ? null : payload.getFamilyId();
        String traceId = payload == null ? null : payload.getTraceId();
        try {
            trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(familyId);
        } catch (ApplicationException | DataAccessException | IllegalStateException | IllegalArgumentException e) {
            // 알람 enqueue 실패는 관측성 이슈로 처리하고, 차감 결과는 그대로 유지한다.
            log.warn(
                    "traffic_shared_threshold_alarm_enqueue_failed traceId={} familyId={}",
                    traceId,
                    familyId,
                    e
            );
        }
    }

    /**
     * 공유풀 실사용 차감량을 legacy family meta 캐시에 반영합니다.
     *
     * <p>Redis-Only 전환 후에는 실제 DB 잔량 메타데이터 갱신 자체가 필요하지 않습니다.
     * 다만 현재 임계치 알람이 family meta cache의 잔량 값을 읽고 있어,
     * EM4에서 임계치 알람 기준을 Redis 잔량 기반으로 바꾸기 전까지만 유지합니다.
     */
    private void safeSyncSharedMetaConsumed(TrafficPayloadReqDto payload, long sharedDeducted) {
        // 메타 갱신 대상(familyId)과 로깅용 traceId를 추출합니다.
        Long familyId = payload == null ? null : payload.getFamilyId();
        String traceId = payload == null ? null : payload.getTraceId();
        // 필수 식별자 또는 차감량이 없으면 write-through를 생략합니다.
        if (familyId == null || familyId <= 0 || sharedDeducted <= 0) {
            return;
        }

        try {
            trafficBalanceStateWriteThroughService.markSharedMetaConsumed(familyId, sharedDeducted);
        } catch (ApplicationException | DataAccessException | IllegalStateException | IllegalArgumentException e) {
            // write-through 실패는 관측성/알람 부가 기능으로 취급하고 핵심 차감 결과는 유지한다.
            log.warn(
                    "traffic_shared_meta_consumed_write_through_failed traceId={} familyId={} deducted={}",
                    traceId,
                    familyId,
                    sharedDeducted,
                    e
            );
        }
    }

    /**
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    private TrafficFinalStatus resolveFinalStatus(
            long apiTotalData,
            long deductedIndividualBytes,
            long deductedSharedBytes,
            long deductedQosBytes,
            long apiRemainingData,
            TrafficLuaStatus lastLuaStatus
    ) {
        // Lua ERROR는 시스템 오류로 간주하여 즉시 FAILED를 반환합니다.
        if (lastLuaStatus == TrafficLuaStatus.ERROR) {
            return TrafficFinalStatus.FAILED;
        }
        if (apiTotalData <= 0L) {
            return TrafficFinalStatus.SUCCESS;
        }
        long deductedTotalBytes = Math.max(0L, deductedIndividualBytes)
                + Math.max(0L, deductedSharedBytes)
                + Math.max(0L, deductedQosBytes);
        if (deductedTotalBytes <= 0L && apiRemainingData == apiTotalData) {
            return TrafficFinalStatus.NOT_DEDUCTED;
        }
        // 남은 요청량이 없으면 전체 요청량 처리 완료로 SUCCESS입니다.
        if (apiRemainingData <= 0) {
            return TrafficFinalStatus.SUCCESS;
        }
        // 일부 요청량만 처리된 경우는 PARTIAL_SUCCESS로 마감합니다.
        return TrafficFinalStatus.PARTIAL_SUCCESS;
    }

    /**
     *  `clampRemaining` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    private long clampRemaining(long value) {
        // 음수 잔량은 의미가 없으므로 0으로 보정합니다.
        if (value <= 0) {
            return 0L;
        }
        // 0보다 큰 값은 그대로 반환해 실제 잔여 요청량을 유지합니다.
        return value;
    }

    /**
     *  비정상 값을 방어하고 안전한 표준 값으로 보정합니다.
     */
    private long normalizeNonNegative(Long value) {
        // null/0/음수 입력은 모두 0으로 정규화해 계산 안정성을 보장합니다.
        if (value == null || value <= 0) {
            return 0L;
        }
        // 양수 입력은 손실 없이 그대로 사용합니다.
        return value;
    }

    /**
     * 선행 정책 검증을 안전하게 수행할 수 있는 최소 필드가 있는지 확인합니다.
     */
    private boolean canRunBlockingPolicyPreCheck(TrafficPayloadReqDto payload) {
        if (payload == null) {
            return false;
        }
        if (payload.getLineId() == null || payload.getLineId() <= 0) {
            return false;
        }
        return payload.getAppId() != null && payload.getAppId() >= 0;
    }

    /**
     * 선행 정책 검증(ensureLoaded + policy check)을 수행하고 결과 계약으로 반환합니다.
     */
    private TrafficPolicyCheckLayerResult evaluateBlockingPolicyCheck(TrafficPayloadReqDto payload) {
        TrafficFailureStage failureStage = TrafficFailureStage.POLICY_CHECK;
        try {
            trafficLinePolicyHydrationService.ensureLoaded(payload.getLineId());
        } catch (DataAccessException | ApplicationException e) {
            RuntimeException unwrapped = trafficRefillOutboxSupportService.unwrapRuntimeException(e);
            if (trafficRedisFailureClassifier.isRetryableInfrastructureFailure(unwrapped)) {
                TrafficStageFailureException stageFailure =
                        TrafficStageFailureException.retryableFailure(failureStage, unwrapped);
                log.warn(
                        "{} traceId={} failureCause={}",
                        failureStage.retryableFailureLogKey(),
                        payload == null ? null : payload.getTraceId(),
                        TrafficPolicyCheckFailureCause.ENSURE_LOADED_RETRYABLE,
                        stageFailure
                );
                return TrafficPolicyCheckLayerResult.retryableFailure(
                        TrafficPolicyCheckFailureCause.ENSURE_LOADED_RETRYABLE,
                        stageFailure
                );
            }
            throw e;
        }
        return trafficPolicyCheckLayerService.evaluate(payload);
    }

    /**
     * 정책 단계 retryable 장애는 오케스트레이터에서 DB fallback으로 전환합니다.
     */
    private TrafficLuaExecutionResult activateDbFallbackForPolicyCheck(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            long requestedDataBytes,
            TrafficDeductExecutionContext context,
            RuntimeException cause
    ) {
        TrafficDeductExecutionContext fallbackContext = prepareDbFallbackContext(context, payload, poolType);
        trafficInFlightDedupeService.markDbFallback(resolveTraceId(fallbackContext, payload));
        trafficDeductFallbackMetrics.incrementDbFallback(poolType.name(), "policy_check_retryable_failure");
        log.warn(
                "traffic_policy_check_retryable_failure_fallback_db traceId={} poolType={} requestedData={}",
                resolveTraceId(fallbackContext, payload),
                poolType,
                requestedDataBytes,
                cause
        );
        return trafficDbDeductFallbackService.deduct(poolType, payload, requestedDataBytes, fallbackContext);
    }

    /**
     * fallback 직전 컨텍스트를 정규화해 전환 상태를 남깁니다.
     */
    private TrafficDeductExecutionContext prepareDbFallbackContext(
            TrafficDeductExecutionContext context,
            TrafficPayloadReqDto payload,
            TrafficPoolType poolType
    ) {
        String traceId = resolveTraceId(context, payload);
        if (context == null) {
            log.warn("traffic_deduct_fallback_context_missing traceId={} poolType={}", traceId, poolType);
            return TrafficDeductExecutionContext.of(traceId);
        }
        return context;
    }

    private String resolveTraceId(TrafficDeductExecutionContext context, TrafficPayloadReqDto payload) {
        if (context != null && context.getTraceId() != null && !context.getTraceId().isBlank()) {
            return context.getTraceId();
        }
        return payload == null ? null : payload.getTraceId();
    }

    /**
     * 오케스트레이션 결과 DTO를 공통 규칙으로 조립합니다.
     */
    private TrafficDeductResultResDto buildOrchestrateResult(
            TrafficPayloadReqDto payload,
            long apiTotalData,
            long deductedIndividualBytes,
            long deductedSharedBytes,
            long deductedQosBytes,
            long apiRemainingData,
            TrafficLuaStatus lastLuaStatus,
            LocalDateTime startedAt
    ) {
        return TrafficDeductResultResDto.builder()
                .traceId(payload == null ? null : payload.getTraceId())
                .apiTotalData(apiTotalData)
                .deductedIndividualBytes(deductedIndividualBytes)
                .deductedSharedBytes(deductedSharedBytes)
                .deductedQosBytes(deductedQosBytes)
                .apiRemainingData(apiRemainingData)
                .finalStatus(resolveFinalStatus(
                        apiTotalData,
                        deductedIndividualBytes,
                        deductedSharedBytes,
                        deductedQosBytes,
                        apiRemainingData,
                        lastLuaStatus
                ))
                .lastLuaStatus(lastLuaStatus)
                .createdAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }
}
