package com.pooli.traffic.mapper;

import java.time.LocalDate;

/**
 * DAILY_TOTAL_DATA bulk insert 한 row에 필요한 값입니다.
 *
 * @param usageDate      일별 사용량 집계 기준 날짜입니다.
 * @param lineId         집계 대상 회선의 고유 식별자입니다.
 * @param totalUsageData 해당 회선의 일일 총 사용 데이터(단위: byte)입니다.
 */
public record DailyTotalUsageBatchInsertRow(
        LocalDate usageDate,
        Long lineId,
        Long totalUsageData
) {
}
