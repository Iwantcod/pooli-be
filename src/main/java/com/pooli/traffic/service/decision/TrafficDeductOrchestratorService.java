package com.pooli.traffic.service.decision;

import java.time.LocalDateTime;

import com.pooli.traffic.service.runtime.TrafficRecentUsageBucketService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ?몃옒??李④컧 ?대깽??1嫄댁쓣 ?⑥씪 ?ъ씠?대줈 泥섎━?섎뒗 ?ㅼ??ㅽ듃?덉씠???쒕퉬?ㅼ엯?덈떎.
 * 媛쒖씤? ?곗꽑 李④컧 ??residual???④퀬 媛쒖씤? ?곹깭媛 NO_BALANCE???뚮쭔 怨듭쑀? 蹂댁셿 李④컧???섑뻾?⑸땲??
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductOrchestratorService {

    private final TrafficHydrateRefillAdapterService trafficHydrateRefillAdapterService;
    private final TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    /**
      * ?대깽??1嫄댁쓽 紐⑺몴 ?곗씠?곕웾(apiTotalData)??泥섎━?섍퀬 理쒖쥌 ?곹깭瑜?諛섑솚?⑸땲??
     */
    public TrafficDeductResultResDto orchestrate(TrafficPayloadReqDto payload) {
        LocalDateTime startedAt = LocalDateTime.now();

        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        long apiRemainingData = apiTotalData;
        long deductedTotalBytes = 0L;
        TrafficLuaStatus lastLuaStatus = null;
        TrafficFinalStatus finalStatus;

        try {
            if (apiRemainingData > 0) {
                TrafficLuaExecutionResult individualResult =
                        trafficHydrateRefillAdapterService.executeIndividualWithRecovery(payload, apiRemainingData);
                lastLuaStatus = individualResult.getStatus();

                long indivDeducted = normalizeNonNegative(individualResult.getAnswer());
                deductedTotalBytes += indivDeducted;
                apiRemainingData = clampRemaining(apiRemainingData - indivDeducted);
                trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, indivDeducted);

                // residual? 媛쒖씤? 泥섎━ ???대깽???붿껌??apiTotalData)?먯꽌 ?⑥? 誘몄쿂由щ웾?낅땲??
                long residualData = apiRemainingData;
                if (residualData > 0 && individualResult.getStatus() == TrafficLuaStatus.NO_BALANCE) {
                    TrafficLuaExecutionResult sharedResult =
                            trafficHydrateRefillAdapterService.executeSharedWithRecovery(payload, residualData);
                    lastLuaStatus = sharedResult.getStatus();

                    long sharedDeducted = normalizeNonNegative(sharedResult.getAnswer());
                    deductedTotalBytes += sharedDeducted;
                    apiRemainingData = clampRemaining(apiRemainingData - sharedDeducted);
                    trafficRecentUsageBucketService.recordUsage(TrafficPoolType.SHARED, payload, sharedDeducted);
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
     * ?낅젰媛믨낵 ?뺤콉??諛뷀깢?쇰줈 理쒖쥌 ?ъ슜 媛믪쓣 怨꾩궛??諛섑솚?⑸땲??
     */
    private TrafficFinalStatus resolveFinalStatus(long apiRemainingData, TrafficLuaStatus lastLuaStatus) {
        if (lastLuaStatus == TrafficLuaStatus.ERROR) {
            // Lua ERROR???쒖뒪???곗씠???ㅻ쪟 ?깃꺽?대?濡?FAILED濡??댁꽍?쒕떎.
            return TrafficFinalStatus.FAILED;
        }
        if (apiRemainingData <= 0) {
            return TrafficFinalStatus.SUCCESS;
        }
        return TrafficFinalStatus.PARTIAL_SUCCESS;
    }

    /**
      * `clampRemaining` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    private long clampRemaining(long value) {
        if (value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * 鍮꾩젙??媛믪쓣 諛⑹뼱?섍퀬 ?덉쟾???쒖? 媛믪쑝濡?蹂댁젙?⑸땲??
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }
}
