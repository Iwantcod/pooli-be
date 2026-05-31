package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase2ReplayServiceTest {

    private static final String WORKER_ID = "restore-worker-1";

    @Mock
    private TrafficDeductDoneLogMapper doneLogMapper;

    @Mock
    private TrafficRestoreReplayLuaExecutor replayLuaExecutor;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisAvailabilityMetrics redisMetrics;

    @InjectMocks
    private TrafficRestorePhase2ReplayService service;

    @Test
    @DisplayName("phase 2는 업무일 범위의 NONE/RETRYABLE done log만 claim한다")
    void claimsOnlyEligibleDoneLogs() {
        LocalDateTime startDateTime = LocalDateTime.of(2026, 5, 27, 0, 0);
        LocalDateTime endDateTime = LocalDateTime.of(2026, 5, 30, 0, 0);
        LocalDateTime leaseExpiredBefore = LocalDateTime.of(2026, 5, 29, 12, 0);
        when(doneLogMapper.selectClaimableRestoreLogsForUpdate(
                startDateTime,
                endDateTime,
                leaseExpiredBefore,
                5000
        )).thenReturn(List.of(doneLog(1L, "NONE"), doneLog(2L, "RETRYABLE")));

        List<TrafficDeductDoneLog> logs =
                service.claim(startDateTime, endDateTime, leaseExpiredBefore, WORKER_ID, 5000);

        assertThat(logs).allMatch(log -> log.getRestoreStatus().equals("NONE")
                || log.getRestoreStatus().equals("RETRYABLE"));
        verify(doneLogMapper).markRestoreLogsProcessing(List.of(1L, 2L), WORKER_ID);
    }

    @Test
    @DisplayName("phase 2 replay 성공 후 done log commit 이후 idempotency key를 제거한다")
    void marksDoneWhenReplayApplied() {
        TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
        when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
                .thenReturn("pooli:restore:idempotency:p2:done_log:10");
        when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(log, WORKER_ID);

            verify(doneLogMapper).markRestoreDoneIfProcessing(10L, WORKER_ID);
            verify(replayLuaExecutor, never()).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("phase 2 worker는 DONE 전환 대상이 없으면 idempotency key를 제거하지 않는다")
    void doesNotDeleteIdempotencyKeyWhenDoneUpdateAffectsNoRows() {
        TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
        when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
                .thenReturn("pooli:restore:idempotency:p2:done_log:10");
        when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(0);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(log, WORKER_ID);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(replayLuaExecutor, never()).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
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
        TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
        when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
                .thenReturn("pooli:restore:idempotency:p2:done_log:10");
        when(replayLuaExecutor.replay(ArgumentMatchers.any()))
                .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
        when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(1);

        // deleteIdempotencyKey 호출 시 강제로 예외가 발생하도록 목(Mock) 행동을 설정합니다.
        doThrow(new RuntimeException("Redis connection failure"))
                .when(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replay(log, WORKER_ID);

            verify(doneLogMapper).markRestoreDoneIfProcessing(10L, WORKER_ID);

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            // afterCommit 내부에서 예외를 발생시키나 밖으로 예외가 던져지지 않아야(swallow) 합니다.
            Assertions.assertDoesNotThrow(() -> {
                synchronizations.forEach(TransactionSynchronization::afterCommit);
            });

            // 멱등키 삭제를 실제로 시도했는지 확인합니다.
            verify(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
            // 정리 실패 메트릭 카운트가 정상 증가했는지 검증합니다.
            verify(redisMetrics).incrementIdempotencyCleanupFailure();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private TrafficDeductDoneLog doneLog(Long id, String restoreStatus) {
        return TrafficDeductDoneLog.builder()
                .trafficDeductDoneId(id)
                .lineId(100L)
                .familyId(200L)
                .appId(30)
                .enqueuedAt(LocalDateTime.of(2026, 5, 27, 10, 0))
                .deductedIndividualBytes(1000L)
                .deductedSharedBytes(2000L)
                .deductedQosBytes(3000L)
                .restoreStatus(restoreStatus)
                .build();
    }
}
