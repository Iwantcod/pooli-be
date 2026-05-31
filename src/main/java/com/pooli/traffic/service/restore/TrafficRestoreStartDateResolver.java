package com.pooli.traffic.service.restore;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.pooli.traffic.mapper.LineDailyBatchJobMapper;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 시작일을 일별 사용량 동기화 완료 이력 기준으로 계산한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestoreStartDateResolver {

    private final LineDailyBatchJobMapper batchJobMapper;

    /**
     * 장애일 이하에서 마지막으로 완료된 일별 동기화 다음 날을 복구 시작일로 반환한다.
     * 완료 이력이 없으면 장애일 당일 done log만 복구하도록 장애일을 반환한다.
     */
    public LocalDate resolve(LocalDate failureDate) {
        LocalDate latestCompletedUsageDate =
                batchJobMapper.selectLatestCompletedUsageSyncDateOnOrBefore(failureDate);
        if (latestCompletedUsageDate == null) {
            return failureDate;
        }
        return latestCompletedUsageDate.plusDays(1);
    }
}
