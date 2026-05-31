package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.mapper.TrafficBalanceSnapshotSourceMapper;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;
import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTargetType;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase0TargetInsertServiceTest {

    @Mock
    private TrafficBalanceSnapshotSourceMapper snapshotSourceMapper;

    @Mock
    private TrafficRestoreHydrateTargetMapper hydrateTargetMapper;

    @InjectMocks
    private TrafficRestorePhase0TargetInsertService service;

    @Test
    @DisplayName("phase 0 target은 daily app 월 데이터와 done log 범위의 line union으로 생성된다")
    void createsLineTargetsFromDailyAppAndDoneLogUnion() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        LocalDate restoreStartDate = LocalDate.of(2026, 5, 27);
        List<YearMonth> months = List.of(YearMonth.of(2026, 5));
        when(snapshotSourceMapper.selectRestoreDailyAppLineIds(List.of(LocalDate.of(2026, 5, 1))))
                .thenReturn(List.of(10L, 20L));
        when(snapshotSourceMapper.selectRestoreDoneLogLineIds(
                restoreStartDate.atStartOfDay(),
                anchorDate.plusDays(1).atStartOfDay()
        )).thenReturn(List.of(20L, 30L));

        List<Long> lineIds = service.resolveLineTargetIds(anchorDate, restoreStartDate, months);

        assertThat(lineIds).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    @DisplayName("phase 0 target insert는 line, family, global policy target을 분리해 생성한다")
    void insertsLineFamilyAndGlobalPolicyTargets() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        LocalDate restoreStartDate = LocalDate.of(2026, 5, 27);
        List<YearMonth> months = List.of(YearMonth.of(2026, 5));
        LocalDate monthStart = LocalDate.of(2026, 5, 1);
        when(snapshotSourceMapper.selectRestoreDailyAppLineIds(List.of(monthStart))).thenReturn(List.of(10L, 20L));
        when(snapshotSourceMapper.selectRestoreDoneLogLineIds(
                restoreStartDate.atStartOfDay(),
                anchorDate.plusDays(1).atStartOfDay()
        )).thenReturn(List.of(20L, 30L));
        when(snapshotSourceMapper.selectRestoreFamilyIdsByLineIds(List.of(10L, 20L, 30L)))
                .thenReturn(List.of(100L, 200L));

        service.insertTargets(BatchName.RESTORE_P0_REDIS_HYDRATE, anchorDate, restoreStartDate, months);

        verify(hydrateTargetMapper).insertIgnoreTargets(
                BatchName.RESTORE_P0_REDIS_HYDRATE.name(),
                monthStart,
                TrafficRestoreHydrateTargetType.LINE,
                List.of(10L, 20L, 30L)
        );
        verify(hydrateTargetMapper).insertIgnoreTargets(
                BatchName.RESTORE_P0_REDIS_HYDRATE.name(),
                monthStart,
                TrafficRestoreHydrateTargetType.FAMILY,
                List.of(100L, 200L)
        );
        verify(hydrateTargetMapper).insertIgnoreTargets(
                BatchName.RESTORE_P0_REDIS_HYDRATE.name(),
                monthStart,
                TrafficRestoreHydrateTargetType.GLOBAL_POLICY,
                List.of(0L)
        );
    }
}
