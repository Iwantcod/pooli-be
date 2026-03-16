package com.pooli.traffic.service.decision;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.entity.TrafficRedisUsageDeltaRecord;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.domain.enums.TrafficRedisUsageDeltaStatus;
import com.pooli.traffic.mapper.TrafficRedisUsageDeltaMapper;

import lombok.RequiredArgsConstructor;

/**
 * DB fallback 동안 누적된 Redis usage delta 레코드 저장/상태전이를 담당합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficUsageDeltaRecordService {

    private final TrafficRedisUsageDeltaMapper trafficRedisUsageDeltaMapper;

    /**
     * fallback 차감 성공분을 replay 대상 레코드로 적재합니다.
     */
    public void record(
            String traceId,
            TrafficPoolType poolType,
            Long lineId,
            Long familyId,
            Integer appId,
            long usedBytes,
            LocalDate usageDate,
            YearMonth targetMonth
    ) {
        if (traceId == null || traceId.isBlank() || poolType == null || lineId == null || lineId <= 0) {
            return;
        }
        if (appId == null || appId < 0 || usedBytes <= 0 || usageDate == null || targetMonth == null) {
            return;
        }

        TrafficRedisUsageDeltaRecord record = TrafficRedisUsageDeltaRecord.builder()
                .traceId(traceId)
                .poolType(poolType)
                .lineId(lineId)
                .familyId(familyId)
                .appId(appId)
                .usedBytes(usedBytes)
                .usageDate(usageDate)
                .targetMonth(targetMonth.toString())
                .status(TrafficRedisUsageDeltaStatus.PENDING)
                .retryCount(0)
                .build();

        trafficRedisUsageDeltaMapper.insertIgnoreDuplicate(record);
    }

    /**
     * replay 대상 레코드를 잠금 조회하고 PROCESSING 상태로 선점합니다.
     */
    @Transactional
    public List<TrafficRedisUsageDeltaRecord> lockReplayCandidatesAndMarkProcessing(int limit, int processingStuckSeconds) {
        List<TrafficRedisUsageDeltaRecord> candidates = trafficRedisUsageDeltaMapper.selectReplayCandidatesForUpdate(
                Math.max(1, limit),
                Math.max(1, processingStuckSeconds)
        );

        for (TrafficRedisUsageDeltaRecord candidate : candidates) {
            if (candidate.getId() == null) {
                continue;
            }
            trafficRedisUsageDeltaMapper.markProcessing(candidate.getId());
        }
        return candidates;
    }

    /**
     * replay 성공 상태를 기록합니다.
     */
    public void markSuccess(long id) {
        trafficRedisUsageDeltaMapper.markSuccess(id);
    }

    /**
     * replay 실패 상태와 재시도 횟수를 갱신합니다.
     */
    public void markFailWithRetryIncrement(long id, String lastErrorMessage) {
        trafficRedisUsageDeltaMapper.markFailWithRetryIncrement(id, truncateError(lastErrorMessage));
    }

    /**
     * 재시도 상한 초과 상태를 기록합니다.
     */
    public void markFailWithRetryCount(long id, int retryCount, String lastErrorMessage) {
        trafficRedisUsageDeltaMapper.markFailWithRetryCount(id, Math.max(0, retryCount), truncateError(lastErrorMessage));
    }

    /**
     * 처리 대기 중인 backlog 개수를 반환합니다.
     */
    public long countBacklog() {
        return trafficRedisUsageDeltaMapper.countBacklog();
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        if (errorMessage.length() <= 255) {
            return errorMessage;
        }
        return errorMessage.substring(0, 255);
    }
}
