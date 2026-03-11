package com.pooli.traffic.service;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDone;
import com.pooli.traffic.mapper.TrafficDeductDoneMapper;

import lombok.RequiredArgsConstructor;

/**
 * 오케스트레이션 결과를 DONE 테이블로 영속화하는 서비스입니다.
 * traceId UNIQUE를 이용해 중복 처리 시 idempotency를 보장합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductDonePersistenceService {

    private final TrafficDeductDoneMapper trafficDeductDoneMapper;

    @Transactional(readOnly = true)
    public boolean existsByTraceId(String traceId) {
        // traceId가 비어 있으면 DONE 조회 대상이 아니므로 false를 반환한다.
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        return trafficDeductDoneMapper.existsByTraceId(traceId);
    }

    @Transactional
    public boolean saveIfAbsent(TrafficPayloadReqDto payload, TrafficDeductResultResDto result) {
        // payload/result는 같은 traceId 기준으로 들어와야 하므로 null 안전성을 먼저 보장한다.
        if (payload == null || result == null) {
            throw new IllegalArgumentException("payload/result must not be null");
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }

        // insertIgnore는 신규 저장이면 1, trace_id 중복이면 0을 반환한다.
        int affectedRowCount = trafficDeductDoneMapper.insertIgnore(
                TrafficDeductDone.builder()
                        .traceId(payload.getTraceId())
                        .lineId(payload.getLineId())
                        .familyId(payload.getFamilyId())
                        .appId(payload.getAppId())
                        .apiTotalData(result.getApiTotalData())
                        .deductedTotalBytes(result.getDeductedTotalBytes())
                        .apiRemainingData(result.getApiRemainingData())
                        .finalStatus(result.getFinalStatus() == null ? null : result.getFinalStatus().name())
                        .lastLuaStatus(result.getLastLuaStatus() == null ? null : result.getLastLuaStatus().name())
                        .createdAt(defaultNowIfNull(result.getCreatedAt()))
                        .finishedAt(defaultNowIfNull(result.getFinishedAt()))
                        .build()
        );

        return affectedRowCount > 0;
    }

    private LocalDateTime defaultNowIfNull(LocalDateTime value) {
        if (value != null) {
            return value;
        }
        return LocalDateTime.now();
    }
}
