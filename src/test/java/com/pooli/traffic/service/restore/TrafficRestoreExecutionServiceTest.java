package com.pooli.traffic.service.restore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.config.TrafficRestoreProperties;
import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.RestoreRange;
import com.pooli.traffic.domain.restore.TrafficRestoreDailyAppTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTargetType;
import com.pooli.traffic.domain.restore.TrafficRestoreTargetStatus;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreExecutionServiceTest {

    @Mock
    private TrafficRestorePhase0TargetInsertService phase0TargetInsertService;

    @Mock
    private TrafficRestoreHydrateTargetMapper hydrateTargetMapper;

    @Mock
    private TrafficRestorePhase0HydrateService phase0HydrateService;

    @Mock
    private TrafficRestorePhase1TargetInsertService phase1TargetInsertService;

    @Mock
    private TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;

    @Mock
    private TrafficRestorePhase1ReplayService phase1ReplayService;

    @Mock
    private TrafficRestorePhase2ReplayService phase2ReplayService;

    @Mock
    private TrafficRestoreVerificationService verificationService;

    @Test
    @DisplayName("복구 실행은 phase 0 hydrate, phase 1 replay, phase 2 replay, 전체 검증 순서로 진행된다")
    void executesRestorePhasesInOrder() {
        LocalDate failureDate = LocalDate.of(2026, 5, 29);
        LocalDate restoreStartDate = LocalDate.of(2026, 5, 29);
        TrafficRestoreProperties properties = new TrafficRestoreProperties();
        properties.setWorkerChunkSize(2);
        properties.setProcessingLeaseTimeoutSeconds(300);
        TrafficRestoreExecutionService service = new TrafficRestoreExecutionService(
                properties,
                phase0TargetInsertService,
                hydrateTargetMapper,
                phase0HydrateService,
                phase1TargetInsertService,
                dailyAppTargetMapper,
                phase1ReplayService,
                phase2ReplayService,
                verificationService
        );
        TrafficRestoreHydrateTarget hydrateTarget = TrafficRestoreHydrateTarget.builder()
                .id(10L)
                .targetMonthStart(LocalDate.of(2026, 5, 1))
                .targetType(TrafficRestoreHydrateTargetType.LINE)
                .targetOwnerId(100L)
                .build();
        TrafficRestoreDailyAppTarget dailyAppTarget = TrafficRestoreDailyAppTarget.builder()
                .id(20L)
                .build();
        TrafficDeductDoneLog doneLog = TrafficDeductDoneLog.builder()
                .trafficDeductDoneId(30L)
                .enqueuedAt(LocalDateTime.of(2026, 5, 29, 12, 0))
                .build();
        when(hydrateTargetMapper.selectClaimableTargetsForUpdate(
                eq(BatchName.RESTORE_P0_REDIS_HYDRATE.name()),
                any(LocalDateTime.class),
                eq(2)
        )).thenReturn(List.of(hydrateTarget), List.of());
        when(dailyAppTargetMapper.selectClaimableTargetsForUpdate(
                eq(BatchName.RESTORE_P1_DAILY_APP_REPLAY.name()),
                any(LocalDateTime.class),
                eq(2)
        )).thenReturn(List.of(dailyAppTarget), List.of());
        when(phase2ReplayService.claim(
                eq(restoreStartDate.atStartOfDay()),
                eq(failureDate.plusDays(1).atStartOfDay()),
                any(LocalDateTime.class),
                any(String.class),
                eq(2)
        )).thenReturn(List.of(doneLog), List.of());

        service.execute(failureDate, restoreStartDate, List.of(YearMonth.of(2026, 5)));

        InOrder inOrder = inOrder(
                phase0TargetInsertService,
                phase0HydrateService,
                phase1TargetInsertService,
                phase1ReplayService,
                phase2ReplayService,
                verificationService
        );
        inOrder.verify(phase0TargetInsertService).insertTargets(
                BatchName.RESTORE_P0_REDIS_HYDRATE,
                failureDate,
                restoreStartDate,
                List.of(YearMonth.of(2026, 5))
        );
        inOrder.verify(phase0HydrateService).hydrate(hydrateTarget);
        inOrder.verify(phase1TargetInsertService).insertTargets(
                BatchName.RESTORE_P1_DAILY_APP_REPLAY,
                restoreStartDate,
                failureDate
        );
        inOrder.verify(phase1ReplayService).replay(eq(dailyAppTarget), any(String.class));
        inOrder.verify(phase2ReplayService).replay(eq(doneLog), any(String.class));
        inOrder.verify(verificationService).verifyAndCorrect(
                failureDate,
                new RestoreRange(restoreStartDate, failureDate.plusDays(1))
        );
        verify(hydrateTargetMapper).markTargetsProcessing(List.of(10L), "restore-start-api");
        verify(hydrateTargetMapper).markTargetTerminalIfProcessing(
                10L,
                TrafficRestoreTargetStatus.DONE,
                "restore-start-api"
        );
        verify(dailyAppTargetMapper).markTargetsProcessing(List.of(20L), "restore-start-api");
    }
}
