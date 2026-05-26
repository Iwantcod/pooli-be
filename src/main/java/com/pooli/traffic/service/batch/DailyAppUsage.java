package com.pooli.traffic.service.batch;

/**
 * DAILY_APP_TOTAL_DATA insert에 필요한 앱별 개인/공유/QoS 사용량 값이다.
 */
record DailyAppUsage(
        Integer applicationId,
        long individualUsageData,
        long sharedUsageData,
        long qosUsageData
) {
}
