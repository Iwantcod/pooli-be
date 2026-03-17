package com.pooli.traffic.service.decision;

import java.time.LocalDateTime;

import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * * 트래픽 차감 이벤트 1건을 단일 사이클로 처리하는 오케스트레이션 서비스입니다.
 * 개인풀 우선 차감 후 residual이 남고 개인풀 상태가 NO_BALANCE일 때만 공유풀 보완 차감을 수행합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductOrchestratorService {

    private final TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;
    private final TrafficRecentUsageBucketService trafficRecentUsageBucketService;
    private final TrafficSharedPoolThresholdAlarmService trafficSharedPoolThresholdAlarmService;

    /**
     * 이벤트 1건의 목표 데이터량(apiTotalData)을 처리하고 최종 상태를 반환합니다.
     */
    public TrafficDeductResultResDto orchestrate(TrafficPayloadReqDto payload) {
        LocalDateTime startedAt = LocalDateTime.now();

        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        long apiRemainingData = apiTotalData;
        long deductedTotalBytes = 0L;
        TrafficLuaStatus lastLuaStatus = null;
        TrafficFinalStatus finalStatus;
        TrafficDeductExecutionContext deductExecutionContext = TrafficDeductExecutionContext.of(
                payload == null ? null : payload.getTraceId()
        );

        try {
            if (apiRemainingData > 0) {
                TrafficLuaExecutionResult individualResult =
                        trafficHydrateRefillAdapterService.executeIndividualWithRecovery(
                                payload,
                                apiRemainingData,
                                deductExecutionContext
                        );
                lastLuaStatus = individualResult.getStatus();

                long indivDeducted = normalizeNonNegative(individualResult.getAnswer());
                deductedTotalBytes += indivDeducted;
                apiRemainingData = clampRemaining(apiRemainingData - indivDeducted);
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, indivDeducted);

                // residual은 개인풀 처리 후 이벤트 요청량(apiTotalData)에서 남은 미처리량입니다.
                long residualData = apiRemainingData;
                if (residualData > 0 && individualResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
                    TrafficLuaExecutionResult sharedResult =
                            trafficHydrateRefillAdapterService.executeSharedWithRecovery(
                                    payload,
                                    residualData,
                                    deductExecutionContext
                            );
                    lastLuaStatus = sharedResult.getStatus();

                    long sharedDeducted = normalizeNonNegative(sharedResult.getAnswer());
                    deductedTotalBytes += sharedDeducted;
                    apiRemainingData = clampRemaining(apiRemainingData - sharedDeducted);
                    trafficRecentUsageBucketService.recordUsage(TrafficPoolType.SHARED, payload, sharedDeducted);
                    if (sharedDeducted > 0) {
                        safeCheckAndEnqueueSharedThresholdAlarm(payload);
                    }
                }
            }

            finalStatus = resolveFinalStatus(apiRemainingData, lastLuaStatus);
        } catch (Exception e) {
            log.error("traffic_orchestrator_failed", e);
            finalStatus = TrafficFinalStatus.FAILED;
            lastLuaStatus = TrafficLuaStatus.ERROR;
        }

        return TrafficDeductResultResDto.builder()
                .traceId(payload == null ? null : payload.getTraceId())
                .apiTotalData(apiTotalData)
                .deductedTotalBytes(deductedTotalBytes)
                .apiRemainingData(apiRemainingData)
                .finalStatus(finalStatus)
                .lastLuaStatus(lastLuaStatus)
                .createdAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 공유풀 임계치 알람은 부가 기능이므로, 실패가 핵심 차감 결과를 오염시키지 않게 보호합니다.
     */
    private void safeCheckAndEnqueueSharedThresholdAlarm(TrafficPayloadReqDto payload) {
        Long familyId = payload == null ? null : payload.getFamilyId();
        String traceId = payload == null ? null : payload.getTraceId();
        try {
            trafficSharedPoolThresholdAlarmService.checkAndEnqueueIfReached(familyId);
        } catch (Exception e) {
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
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    private TrafficFinalStatus resolveFinalStatus(long apiRemainingData, TrafficLuaStatus lastLuaStatus) {
        if (lastLuaStatus == TrafficLuaStatus.ERROR) {
        	// Lua ERROR는 시스템/데이터 오류 성격이므로 FAILED로 해석한다.
            return TrafficFinalStatus.FAILED;
        }
        if (apiRemainingData <= 0) {
            return TrafficFinalStatus.SUCCESS;
        }
        return TrafficFinalStatus.PARTIAL_SUCCESS;
    }

    /**
     *  `clampRemaining` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    private long clampRemaining(long value) {
        if (value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     *  비정상 값을 방어하고 안전한 표준 값으로 보정합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }
}
