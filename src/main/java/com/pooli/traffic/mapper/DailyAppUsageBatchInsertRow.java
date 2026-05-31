package com.pooli.traffic.mapper;

import java.time.LocalDate;

/**
 * DAILY_APP_TOTAL_DATA multi-value insert 한 row에 필요한 값이다.
 *
 * @param usageDate           앱별 사용량 집계 기준 날짜입니다.
 * @param lineId              앱별 사용량이 귀속되는 회선의 고유 식별자입니다.
 * @param applicationId       집계 대상이 되는 애플리케이션의 고유 식별자입니다.
 * @param individualUsageData 해당 애플리케이션의 일일 개별 제공량 사용 데이터(단위: byte)입니다.
 * @param sharedUsageData     해당 애플리케이션의 일일 공유 제공량 사용 데이터(단위: byte)입니다.
 * @param qosUsageData        해당 애플리케이션의 일일 QoS(속도 제어) 상태에서의 사용 데이터(단위: byte)입니다.
 */
public record DailyAppUsageBatchInsertRow(
        LocalDate usageDate,
        Long lineId,
        Integer applicationId,
        long individualUsageData,
        long sharedUsageData,
        long qosUsageData
) {
}
