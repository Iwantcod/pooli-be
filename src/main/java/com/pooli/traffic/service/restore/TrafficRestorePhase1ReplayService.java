package com.pooli.traffic.service.restore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;

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
     */
    private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
                } catch (Exception cleanupFailure) {
                    // 커밋은 완료되었으므로, 정리 실패가 다음 비즈니스 replay의 처리를 방해하지 않도록 예외를 차단(Swallow)합니다.
                    log.warn("Failed to delete phase 2 idempotency key: {}", idempotencyKey, cleanupFailure);
                    // TODO: cleanupFailureMetric.increment(); // 후처리 실패 모니터링 메트릭 카운트 증가
                }
            }
        });
    }
}
