package com.pooli.traffic.service.decision;

import java.time.LocalDateTime;

import com.pooli.common.exception.ApplicationException;
import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import com.pooli.traffic.service.runtime.TrafficBalanceStateWriteThroughService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
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
    private final TrafficBalanceStateWriteThroughService trafficBalanceStateWriteThroughService;

    /**
     * 이벤트 1건의 목표 데이터량(apiTotalData)을 처리하고 최종 상태를 반환합니다.
     */
    public TrafficDeductResultResDto orchestrate(TrafficPayloadReqDto payload) {
        // 요청 처리 시작 시각을 기록해 단건 처리 레이턴시를 추적할 수 있게 합니다.
        LocalDateTime startedAt = LocalDateTime.now();

        // 메시지 기준 목표 차감량(apiTotalData)과 누적 차감량 상태를 초기화합니다.
        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        long apiRemainingData = apiTotalData;
        long deductedTotalBytes = 0L;
        TrafficLuaStatus lastLuaStatus = null;
        TrafficFinalStatus finalStatus;
        // 개인/공유 차감 재시도 경로에서 traceId 단위 상태를 공유하기 위한 컨텍스트를 준비합니다.
        TrafficDeductExecutionContext deductExecutionContext = TrafficDeductExecutionContext.of(
                payload == null ? null : payload.getTraceId()
        );

        if (apiRemainingData > 0) {
            // 1순위로 개인풀 차감을 시도합니다.
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
            // 리필 계획 계산을 위해 최근 사용량 버킷을 개인풀 기준으로 기록합니다.
            trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, indivDeducted);

            // residual은 개인풀 처리 후 이벤트 요청량(apiTotalData)에서 남은 미처리량입니다.
            long residualData = apiRemainingData;
            if (residualData > 0 && individualResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
                // 개인풀이 잔량 부족일 때만 공유풀 보완 차감을 수행합니다.
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
                // 공유풀 사용량도 동일하게 버킷에 기록해 다음 리필 계획 계산에 반영합니다.
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.SHARED, payload, sharedDeducted);
                if (sharedDeducted > 0) {
                    // 부가 메타 동기화/임계치 알람은 실패해도 핵심 차감 결과를 바꾸지 않습니다.
                    safeSyncSharedMetaConsumed(payload, sharedDeducted);
                    safeCheckAndEnqueueSharedThresholdAlarm(payload);
                }
            }
        }
        // 남은 요청량과 마지막 Lua 상태를 기준으로 최종 상태를 계산합니다.
        finalStatus = resolveFinalStatus(apiRemainingData, lastLuaStatus);

        // 단건 처리 결과를 최종 응답 DTO로 조립합니다.
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
     * 공유풀 실사용 차감량을 family meta 캐시에 반영합니다.
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
    private TrafficFinalStatus resolveFinalStatus(long apiRemainingData, TrafficLuaStatus lastLuaStatus) {
        // Lua ERROR는 시스템 오류로 간주하여 즉시 FAILED를 반환합니다.
        if (lastLuaStatus == TrafficLuaStatus.ERROR) {
            return TrafficFinalStatus.FAILED;
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
}
