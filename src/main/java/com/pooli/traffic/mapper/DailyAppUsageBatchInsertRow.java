package com.pooli.traffic.mapper;

/**
 * DAILY_APP_TOTAL_DATA multi-value insert 한 row에 필요한 값이다.
 */
public record DailyAppUsageBatchInsertRow(
        Integer applicationId,
        long individualUsageData,
        long sharedUsageData,
        long qosUsageData
) {
}
