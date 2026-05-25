package com.pooli.traffic.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;

/**
 * LINE_DAILY_BATCH_JOB metadata 조회/생성을 담당한다.
 * DB unique key를 두지 않으므로 생성 전 기존 row 조회는 서비스 절차에서 반드시 먼저 수행한다.
 */
@Mapper
public interface LineDailyBatchJobMapper {

    /**
     * 자동 실행은 같은 batch_name + usage_date의 최신 row가 있으면 상태와 무관하게 그 row를 사용한다.
     */
    LineDailyBatchJob selectLatestByBatchNameAndUsageDate(
            @Param("batchName") BatchName batchName,
            @Param("usageDate") LocalDate usageDate
    );

    /**
     * 신규 자동 실행 metadata를 PENDING 상태로 생성한다.
     */
    int insert(LineDailyBatchJob batchJob);
}
