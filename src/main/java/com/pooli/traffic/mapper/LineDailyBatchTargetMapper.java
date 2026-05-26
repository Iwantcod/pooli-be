package com.pooli.traffic.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;

/**
 * LINE_DAILY_BATCH_TARGET target set 조회를 담당한다.
 * target row 생성과 worker claim 갱신 SQL은 각 실행 마일스톤에서 별도로 추가한다.
 */
@Mapper
public interface LineDailyBatchTargetMapper {

    /**
     * usage_date + line_id unique key 기준으로 단일 target row를 조회한다.
     */
    LineDailyBatchTarget selectByUsageDateAndLineId(
            @Param("usageDate") LocalDate usageDate,
            @Param("lineId") Long lineId
    );

    /**
     * batch metadata id 없이 usage_date 기준 target set 크기를 조회한다.
     */
    long countByUsageDate(@Param("usageDate") LocalDate usageDate);
}
