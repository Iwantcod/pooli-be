package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.repository.TrafficDeductDoneLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 트래픽 차감 완료 로그를 MongoDB로 저장하고 중복(traceId) 여부를 조회하는 서비스입니다.
 * traceId 고유 인덱스로 idempotency를 보장하고, loggedAt TTL 인덱스로 90일 보관 정책을 적용합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductDoneLogService {

    static final String COLLECTION_NAME = "traffic_deduct_done_log";
    static final String TRACE_ID_FIELD = "trace_id";
    static final String LOGGED_AT_FIELD = "logged_at";
    static final long LOG_TTL_SECONDS = 7_776_000L; // 90 days

    private final TrafficDeductDoneLogRepository trafficDeductDoneLogRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * 애플리케이션 시작 시 필요한 인덱스를 보장합니다.
     */
    @PostConstruct
    public void ensureIndexes() {
        IndexOperations indexOperations = mongoTemplate.indexOps(COLLECTION_NAME);

        // trace_id UNIQUE로 동일 요청의 중복 완료 처리를 방지한다.
        indexOperations.createIndex(
                new Index()
                        .on(TRACE_ID_FIELD, Sort.Direction.ASC)
                        .unique()
                        .named("uk_traffic_deduct_done_log_trace_id")
        );

        // logged_at TTL 인덱스로 완료 로그를 90일 이후 자동 만료한다.
        indexOperations.createIndex(
                new Index()
                        .on(LOGGED_AT_FIELD, Sort.Direction.ASC)
                        .expire(LOG_TTL_SECONDS, TimeUnit.SECONDS)
                        .named("idx_traffic_deduct_done_log_logged_at_ttl")
        );
    }

    /**
     * traceId 완료 이력이 존재하는지 확인합니다.
     */
    public boolean existsByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        return trafficDeductDoneLogRepository.existsByTraceId(traceId);
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

        try {
            trafficDeductDoneLogRepository.insert(
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
                            .createdAt(defaultNowIfNull(result.getCreatedAt()))
                            .finishedAt(defaultNowIfNull(result.getFinishedAt()))
                            .loggedAt(LocalDateTime.now())
                            .latency(latency)
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
}
