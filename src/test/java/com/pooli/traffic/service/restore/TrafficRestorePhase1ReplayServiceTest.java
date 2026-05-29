package com.pooli.traffic.service.restore;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase1ReplayServiceTest {

    private static final String WORKER_ID = "restore-worker-1";

    @Mock
    private TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;

    @Mock
    private TrafficRestoreReplayLuaExecutor replayLuaExecutor;

    @InjectMocks
    private TrafficRestorePhase1ReplayService service;

    @Test
    @DisplayName("phase 1 target insert는 복구 날짜 범위의 daily app row를 target으로 생성한다")
    void insertsDailyAppTargetsForRestoreDateRange() {
        TrafficRestorePhase1TargetInsertService targetInsertService =
                new TrafficRestorePhase1TargetInsertService(dailyAppTargetMapper);
        LocalDate startDate = LocalDate.of(2026, 5, 27);
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);

        targetInsertService.insertTargets(BatchName.RESTORE_P1_DAILY_APP_REPLAY, startDate, anchorDate);

        verify(dailyAppTargetMapper).insertIgnoreTargetsFromDailyApp(
                BatchName.RESTORE_P1_DAILY_APP_REPLAY.name(),
                startDate,
                anchorDate.plusDays(1)
        );
    }

    @Test
    @DisplayName("phase 1 worker는 replay 성공 후 target을 DONE으로 전환한다")
    void marksTargetDoneWhenReplayApplied() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));

        service.replay(target, WORKER_ID);

        verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        );
        verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
    }

    @Test
    @DisplayName("phase 1 worker는 idempotency skip이어도 target을 DONE으로 전환한다")
    void marksTargetDoneWhenReplaySkippedByIdempotency() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("SKIPPED", null));

        service.replay(target, WORKER_ID);

        verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        );
        verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
    }

    private TrafficRestoreDailyAppTarget target() {
        return TrafficRestoreDailyAppTarget.builder()
                .id(10L)
                .batchName(BatchName.RESTORE_P1_DAILY_APP_REPLAY.name())
                .usageDate(LocalDate.of(2026, 5, 27))
                .lineId(100L)
                .applicationId(20)
                .status(TrafficRestoreTargetStatus.PROCESSING)
                .build();
    }

    private TrafficRestoreReplayCommand command() {
        return TrafficRestoreReplayCommand.builder()
                .idempotencyKey("pooli:restore:idempotency:p1:daily_app:20260527:100:20")
                .usageDate(LocalDate.of(2026, 5, 27))
                .lineId(100L)
                .familyId(200L)
                .applicationId(20)
                .individualUsageBytes(1000L)
                .sharedUsageBytes(2000L)
                .qosUsageBytes(3000L)
                .expireEpochSeconds(1_780_000_000L)
                .build();
    }
}
