package com.pooli.traffic.service.restore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                workerId
        );
        replayLuaExecutor.deleteIdempotencyKey(command.getIdempotencyKey());
    }
}
