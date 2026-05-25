package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchJobCreateResult;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;

import lombok.RequiredArgsConstructor;

/**
 * 일별 배치 metadata 생성 진입점이다.
 * 자동 실행 경로에서는 기존 row 확인 후 없을 때만 PENDING row를 생성한다.
 */
@Service
@RequiredArgsConstructor
public class LineDailyBatchJobService {

    private final LineDailyBatchJobMapper lineDailyBatchJobMapper;

    /**
     * 자동 실행용 batch metadata를 생성하거나 기존 row를 반환한다.
     * 같은 batch_name + usage_date row가 이미 있으면 terminal 상태라도 신규 row를 만들지 않는다.
     */
    @Transactional
    public LineDailyBatchJobCreateResult createPendingForAutomaticRunIfAbsent(
            BatchName batchName,
            LocalDate usageDate
    ) {
        // 1. 동일 날짜의 기존 metadata가 있으면 완료/실패 상태라도 자동 신규 실행을 만들지 않는다.
        LineDailyBatchJob existing =
                lineDailyBatchJobMapper.selectLatestByBatchNameAndUsageDate(batchName, usageDate);
        if (existing != null) {
            return new LineDailyBatchJobCreateResult(false, existing);
        }

        // 2. 기존 metadata가 없을 때만 line 단위 count를 0으로 초기화한 PENDING row를 생성한다.
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .batchName(batchName)
                .usageDate(usageDate)
                .status(LineDailyBatchStatus.PENDING)
                .targetCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .skippedCount(0L)
                .build();
        lineDailyBatchJobMapper.insert(batchJob);

        return new LineDailyBatchJobCreateResult(true, batchJob);
    }
}
