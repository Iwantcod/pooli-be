package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase1ReplayServiceTest {

    private static final String WORKER_ID = "restore-worker-1";

    @Mock
    private TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;

    @Mock
    private TrafficRestoreReplayLuaExecutor replayLuaExecutor;

    @Mock
    private TrafficRedisAvailabilityMetrics redisMetrics;

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
    @DisplayName("phase 1 worker는 DONE 전환 commit 이후 idempotency key를 제거한다")
    void marksTargetDoneWhenReplayApplied() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(target, WORKER_ID);

            verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                    target.getId(),
                    TrafficRestoreTargetStatus.DONE,
                    WORKER_ID
            );
            verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("phase 1 worker는 idempotency skip이어도 DONE 전환 commit 이후 key를 제거한다")
    void marksTargetDoneWhenReplaySkippedByIdempotency() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("SKIPPED", null));
        when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(target, WORKER_ID);

            verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                    target.getId(),
                    TrafficRestoreTargetStatus.DONE,
                    WORKER_ID
            );
            verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("phase 1 worker는 DONE 전환 ownership을 잃으면 idempotency key를 제거하지 않는다")
    void doesNotDeleteIdempotencyKeyWhenDoneUpdateAffectsNoRows() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(0);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(target, WORKER_ID);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("멱등키 삭제 중 예외 발생 시 예외를 전파하지 않고 가용성 실패 메트릭을 증가시킨다")
    /**
     * 트랜잭션 커밋 완료 이후 멱등키 삭제를 수행할 때 예외가 발생하더라도,
     * 사용자 스레드로 예외를 전파하지 않고 정상적으로 삼키며 실패 메트릭을 증가시키는지 검증합니다.
     */
    void swallowsExceptionAndIncrementsMetricWhenCleanupFails() {
        TrafficRestoreDailyAppTarget target = target();
        TrafficRestoreReplayCommand command = command();
        when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
        when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        )).thenReturn(1);

        // deleteIdempotencyKey 호출 시 강제로 예외가 발생하도록 목(Mock) 행동을 설정합니다.
        doThrow(new RuntimeException("Redis connection failure"))
                .when(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(target, WORKER_ID);

            verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                    target.getId(),
                    TrafficRestoreTargetStatus.DONE,
                    WORKER_ID
            );

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            // afterCommit 내부에서 예외를 발생시키나 밖으로 예외가 던져지지 않아야(swallow) 합니다.
            Assertions.assertDoesNotThrow(() -> {
                synchronizations.forEach(TransactionSynchronization::afterCommit);
            });

            // 멱등키 삭제를 실제로 시도했는지 확인합니다.
            verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
            // 정리 실패 메트릭 카운트가 정상 증가했는지 검증합니다.
            verify(redisMetrics).incrementIdempotencyCleanupFailure();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
