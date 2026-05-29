package com.pooli.traffic.service.restore;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase 2에서 TRAFFIC_DEDUCT_DONE 기준 사용량을 replay한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestorePhase2ReplayService {

    private static final String RESTORE_DONE_LOG_IDEMPOTENCY_SCOPE = "p2:done_log";

    private final TrafficDeductDoneLogMapper doneLogMapper;
    private final TrafficRestoreReplayLuaExecutor replayLuaExecutor;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
     * 업무일 범위의 claim 가능한 done log를 PROCESSING으로 선점한다.
     */
    @Transactional
    public List<TrafficDeductDoneLog> claim(
            LocalDateTime startInclusive,
            LocalDateTime endExclusive,
            LocalDateTime leaseExpiredBefore,
            String workerId,
            int limit
    ) {
        List<TrafficDeductDoneLog> logs = doneLogMapper.selectClaimableRestoreLogsForUpdate(
                startInclusive,
                endExclusive,
                leaseExpiredBefore,
                limit
        );
        if (logs.isEmpty()) {
            return logs;
        }

        doneLogMapper.markRestoreLogsProcessing(
                logs.stream()
                        .map(TrafficDeductDoneLog::getTrafficDeductDoneId)
                        .toList(),
                workerId
        );
        return logs;
    }

    /**
     * PROCESSING done log 하나를 replay하고 성공 또는 idempotency skip이면 DONE으로 닫는다.
     */
    @Transactional
    public void replay(TrafficDeductDoneLog log, String workerId) {
        TrafficRestoreReplayCommand command = toReplayCommand(log);
        TrafficRestoreReplayResult result = replayLuaExecutor.replay(command);
        if (!"APPLIED".equals(result.status()) && !"SKIPPED".equals(result.status())) {
            doneLogMapper.markRestoreFailedIfProcessing(
                    log.getTrafficDeductDoneId(),
                    workerId,
                    result.message()
            );
            return;
        }

        doneLogMapper.markRestoreDoneIfProcessing(log.getTrafficDeductDoneId(), workerId);
        replayLuaExecutor.deleteIdempotencyKey(command.getIdempotencyKey());
    }

    private TrafficRestoreReplayCommand toReplayCommand(TrafficDeductDoneLog log) {
        return TrafficRestoreReplayCommand.builder()
                .idempotencyKey(trafficRedisKeyFactory.restoreIdempotencyKey(
                        RESTORE_DONE_LOG_IDEMPOTENCY_SCOPE,
                        String.valueOf(log.getTrafficDeductDoneId())
                ))
                .usageDate(log.getEnqueuedAt().toLocalDate())
                .lineId(log.getLineId())
                .familyId(log.getFamilyId())
                .applicationId(log.getAppId())
                .individualUsageBytes(log.getDeductedIndividualBytes())
                .sharedUsageBytes(log.getDeductedSharedBytes())
                .qosUsageBytes(log.getDeductedQosBytes())
                .expireEpochSeconds(0L)
                .build();
    }
}
