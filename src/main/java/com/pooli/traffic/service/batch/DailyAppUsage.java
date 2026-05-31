package com.pooli.traffic.service.batch;

/**
 * DAILY_APP_TOTAL_DATA insert에 필요한 앱별 개인/공유/QoS 사용량 값이다.
 *
 * @param applicationId       집계 대상이 되는 애플리케이션의 고유 식별자입니다.
 * @param individualUsageData 해당 애플리케이션의 일일 개별 제공량 사용 데이터(단위: byte)입니다.
 * @param sharedUsageData     해당 애플리케이션의 일일 공유 제공량 사용 데이터(단위: byte)입니다.
 * @param qosUsageData        해당 애플리케이션의 일일 QoS(속도 제어) 상태에서의 사용 데이터(단위: byte)입니다.
 */
record DailyAppUsage(
        Integer applicationId,
        long individualUsageData,
        long sharedUsageData,
        long qosUsageData
) {
}
