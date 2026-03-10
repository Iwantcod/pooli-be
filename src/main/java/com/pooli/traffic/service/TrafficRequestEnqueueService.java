package com.pooli.traffic.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.traffic.domain.TrafficStreamFields;
import com.pooli.traffic.domain.dto.request.TrafficGenerateReqDto;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficGenerateResDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 트래픽 발생 요청을 Streams에 적재하는 API 서버 전용 서비스입니다.
 * 명세에 따라 traceId/enqueuedAt을 생성하고 payload(JSON) 형태로 저장합니다.
 */
@Slf4j
@Service
@Profile({"local", "api"})
@RequiredArgsConstructor
public class TrafficRequestEnqueueService {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Qualifier("streamsStringRedisTemplate")
    private final StringRedisTemplate streamsStringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AppStreamsProperties appStreamsProperties;

    public TrafficGenerateResDto enqueue(TrafficGenerateReqDto request) {
        String traceId = resolveTraceId();
        long enqueuedAt = System.currentTimeMillis();

        TrafficPayloadReqDto payload = TrafficPayloadReqDto.builder()
                .traceId(traceId)
                .lineId(request.getLineId())
                .familyId(request.getFamilyId())
                .appId(request.getAppId())
                .apiTotalData(request.getApiTotalData())
                .enqueuedAt(enqueuedAt)
                .build();

        String payloadJson = toPayloadJson(payload);

        // Streams 명세(field=payload, value=json)에 맞춰 단일 레코드를 적재한다.
        RecordId recordId = addToStream(payloadJson);

        log.info(
                "traffic_enqueue_success traceId={} streamKey={} recordId={}",
                traceId,
                appStreamsProperties.getKeyTrafficRequest(),
                recordId.getValue()
        );

        return TrafficGenerateResDto.builder()
                .traceId(traceId)
                .enqueuedAt(enqueuedAt)
                .build();
    }

    private String resolveTraceId() {
        // 요청 필터(MDC)에 이미 traceId가 있으면 같은 값을 재사용해
        // API 로그와 MQ 메시지 간 상관관계를 쉽게 추적한다.
        String existingTraceId = MDC.get(TRACE_ID_MDC_KEY);
        return existingTraceId != null && !existingTraceId.isBlank()
                ? existingTraceId
                : UUID.randomUUID().toString();
    }

    private String toPayloadJson(TrafficPayloadReqDto payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("traffic_enqueue_serialize_failed traceId={}", payload.getTraceId(), e);
            throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "payload 직렬화에 실패했습니다.");
        }
    }

    private RecordId addToStream(String payloadJson) {
        try {
            RecordId recordId = streamsStringRedisTemplate.opsForStream().add(
                    StreamRecords.string(
                            Map.of(TrafficStreamFields.PAYLOAD, payloadJson)
                    ).withStreamKey(appStreamsProperties.getKeyTrafficRequest())
            );

            if (recordId == null) {
                throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Streams 적재 결과가 비어 있습니다.");
            }

            return recordId;
        } catch (DataAccessException e) {
            log.error("traffic_enqueue_stream_failed streamKey={}", appStreamsProperties.getKeyTrafficRequest(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Streams 적재에 실패했습니다.");
        }
    }
}

