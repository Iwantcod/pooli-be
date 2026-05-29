package com.pooli.traffic.service.restore;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
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
     */
    public LocalDate resolve(LocalDate failureDate) {
        LocalDate latestCompletedUsageDate =
                batchJobMapper.selectLatestCompletedUsageSyncDateOnOrBefore(failureDate);
        if (latestCompletedUsageDate == null) {
            throw new ApplicationException(
                    CommonErrorCode.INVALID_REQUEST_PARAM,
                    "장애일 이전에 완료된 일별 사용량 동기화 배치가 없습니다."
            );
        }
        return latestCompletedUsageDate.plusDays(1);
    }
}
