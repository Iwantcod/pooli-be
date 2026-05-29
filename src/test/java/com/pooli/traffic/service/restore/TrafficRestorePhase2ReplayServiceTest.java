package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase2ReplayServiceTest {

    private static final String WORKER_ID = "restore-worker-1";

    @Mock
    private TrafficDeductDoneLogMapper doneLogMapper;

    @Mock
    private TrafficRestoreReplayLuaExecutor replayLuaExecutor;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

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
    @DisplayName("phase 2 replay 성공 후 done log를 DONE으로 전환하고 idempotency key를 제거한다")
    void marksDoneWhenReplayApplied() {
        TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
        when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
                .thenReturn("pooli:restore:idempotency:p2:done_log:10");
        when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));

        service.replay(log, WORKER_ID);

        verify(doneLogMapper).markRestoreDoneIfProcessing(10L, WORKER_ID);
        verify(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
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
