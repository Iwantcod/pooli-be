package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 트래픽 차감 완료 로그를 MySQL에 저장하고 중복(traceId) 여부를 조회하는 서비스입니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductDoneLogService {

    private final TrafficDeductDoneLogMapper trafficDeductDoneLogMapper;

    /**
     * traceId 완료 이력이 존재하는지 확인합니다.
     */
    public boolean existsByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        return trafficDeductDoneLogMapper.existsByTraceId(traceId);
    }

    /**
     * 완료 로그를 신규로 저장합니다.
     *
     * @return 신규 저장이면 true, traceId 중복이면 false
     */
    public boolean saveIfAbsent(
            TrafficPayloadReqDto payload,
            TrafficDeductResultResDto result,
            String recordId,
            Long latency
    ) {
        if (payload == null || result == null) {
            throw new IllegalArgumentException("payload/result must not be null");
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }

        LocalDateTime startedAt = defaultNowIfNull(result.getCreatedAt());
        try {
            trafficDeductDoneLogMapper.insert(
                    TrafficDeductDoneLog.builder()
                            .traceId(payload.getTraceId())
                            .recordId(recordId)
                            .lineId(payload.getLineId())
                            .familyId(payload.getFamilyId())
                            .appId(payload.getAppId())
                            .apiTotalData(result.getApiTotalData())
                            .deductedTotalBytes(result.getDeductedTotalBytes())
                            .apiRemainingData(result.getApiRemainingData())
                            .finalStatus(result.getFinalStatus() == null ? null : result.getFinalStatus().name())
                            .lastLuaStatus(result.getLastLuaStatus() == null ? null : result.getLastLuaStatus().name())
                            .startedAt(startedAt)
                            .finishedAt(defaultNowIfNull(result.getFinishedAt()))
                            .latency(normalizeLatency(latency))
                            .build()
            );
            return true;
        } catch (DuplicateKeyException e) {
            // 이미 같은 trace_id가 존재하면 정상적인 중복 완료로 간주한다.
            return false;
        }
    }

    /**
     * 입력값이 없을 때 사용할 기본 시간을 반환합니다.
     */
    private LocalDateTime defaultNowIfNull(LocalDateTime value) {
        if (value != null) {
            return value;
        }
        return LocalDateTime.now();
    }

    /**
     * 비정상 음수 지연값이 DB에 저장되지 않도록 0 이상으로 정규화합니다.
     */
    private long normalizeLatency(Long latency) {
        if (latency == null) {
            return 0L;
        }
        return Math.max(0L, latency);
    }
}
