package com.pooli.traffic.service.restore;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.mapper.TrafficRestoreDailyAppTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase 1 daily app replay target row 생성을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestorePhase1TargetInsertService {

    private final TrafficRestoreDailyAppTargetMapper dailyAppTargetMapper;

    /**
     * 복구 대상 날짜 범위의 DAILY_APP_TOTAL_DATA row를 phase 1 target으로 생성한다.
     */
    @Transactional
    public void insertTargets(BatchName batchName, LocalDate restoreStartDate, LocalDate anchorDate) {
        dailyAppTargetMapper.insertIgnoreTargetsFromDailyApp(
                batchName.name(),
                restoreStartDate,
                anchorDate.plusDays(1)
        );
    }
}
