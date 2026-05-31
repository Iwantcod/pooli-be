package com.pooli.traffic.service.restore;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase 1 daily app target을 Redis replay 후 DONE 처리한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficRestorePhase1ReplayService {

    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;
    private final TrafficRestoreReplayLuaExecutor replayLuaExecutor;
    private final TrafficRedisAvailabilityMetrics redisMetrics;

    /**
     * PROCESSING target 하나를 replay하고 성공 또는 idempotency skip이면 DONE으로 닫는다.
     */
    @Transactional
    public void replay(TrafficRestoreDailyAppTarget target, String workerId) {
        TrafficRestoreReplayCommand command = dailyAppTargetMapper.selectReplayCommand(target.getId());
        TrafficRestoreReplayResult result = replayLuaExecutor.replay(command);
        if (!"APPLIED".equals(result.status()) && !"SKIPPED".equals(result.status())) {
            dailyAppTargetMapper.markTargetTerminalIfProcessing(
                    target.getId(),
                    TrafficRestoreTargetStatus.FAILED,
                    workerId
            );
            return;
        }

        int updated = dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                workerId
        );
        if (updated == 1) {
            registerIdempotencyCleanupAfterCommit(command.getIdempotencyKey());
        }
    }

    /**
     * DB terminal 상태 전환 commit이 확정된 뒤에만 Redis replay idempotency key를 제거한다.
     * 정리 중 오류가 발생하더라도 트랜잭션 결과에 영향을 주지 않도록 예외를 포획하고 경고 로그와 가용성 실패 메트릭을 기록한다.
     *
     * @param idempotencyKey 삭제할 멱등성 키
     */
    private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Redis에서 복구 작업용 멱등성 키를 삭제합니다.
                    replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
                } catch (Exception cleanupFailure) {
                    // 멱등키 삭제 실패 시 예외를 포획하고 경고 로그와 메트릭을 기록
                    LoggerFactory.getLogger(TrafficRestorePhase1ReplayService.class)
                            .warn("Failed to delete phase 1 idempotency key: {}", idempotencyKey, cleanupFailure);
                    redisMetrics.incrementIdempotencyCleanupFailure();
                }
            }
        });
    }
}
