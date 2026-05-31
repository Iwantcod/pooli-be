package com.pooli.traffic.service.restore;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.mapper.TrafficBalanceSnapshotSourceMapper;
import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTargetType;
import com.pooli.traffic.mapper.TrafficRestoreHydrateTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase 0 hydrate target source를 산출한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestorePhase0TargetInsertService {

    private final TrafficBalanceSnapshotSourceMapper snapshotSourceMapper;
    private final TrafficRestoreHydrateTargetMapper hydrateTargetMapper;

    /**
     * daily app 월 데이터와 done log 복구 범위에 등장한 line_id union을 반환한다.
     */
    @Transactional(readOnly = true)
    public List<Long> resolveLineTargetIds(
            LocalDate anchorDate,
            LocalDate restoreStartDate,
            List<YearMonth> targetMonths
    ) {
        List<LocalDate> monthStarts = targetMonths.stream()
                .map(month -> month.atDay(1))
                .toList();

        Set<Long> lineIds = new LinkedHashSet<>();
        lineIds.addAll(snapshotSourceMapper.selectRestoreDailyAppLineIds(monthStarts));
        lineIds.addAll(snapshotSourceMapper.selectRestoreDoneLogLineIds(
                restoreStartDate.atStartOfDay(),
                anchorDate.plusDays(1).atStartOfDay()
        ));
        return List.copyOf(lineIds);
    }

    /**
     * 복구 대상 월마다 LINE, FAMILY, GLOBAL_POLICY hydrate target row를 생성한다.
     */
    @Transactional
    public void insertTargets(
            BatchName batchName,
            LocalDate anchorDate,
            LocalDate restoreStartDate,
            List<YearMonth> targetMonths
    ) {
        List<Long> lineIds = resolveLineTargetIds(anchorDate, restoreStartDate, targetMonths);
        List<Long> familyIds = lineIds.isEmpty()
                ? List.of()
                : snapshotSourceMapper.selectRestoreFamilyIdsByLineIds(lineIds);

        for (YearMonth targetMonth : targetMonths) {
            LocalDate monthStart = targetMonth.atDay(1);
            insertIfNotEmpty(batchName, monthStart, TrafficRestoreHydrateTargetType.LINE, lineIds);
            insertIfNotEmpty(batchName, monthStart, TrafficRestoreHydrateTargetType.FAMILY, familyIds);
            hydrateTargetMapper.insertIgnoreTargets(
                    batchName.name(),
                    monthStart,
                    TrafficRestoreHydrateTargetType.GLOBAL_POLICY,
                    List.of(0L)
            );
        }
    }

    private void insertIfNotEmpty(
            BatchName batchName,
            LocalDate monthStart,
            TrafficRestoreHydrateTargetType targetType,
            List<Long> ownerIds
    ) {
        if (ownerIds.isEmpty()) {
            return;
        }
        hydrateTargetMapper.insertIgnoreTargets(batchName.name(), monthStart, targetType, ownerIds);
    }
}
