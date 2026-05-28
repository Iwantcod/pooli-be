package com.pooli.traffic.service.batch;

/**
 * Redis 앱 사용량 hash의 여러 source field를 앱 단위 결과로 만들기 전 임시로 합산하는 내부 상태이다.
 */
class DailyAppUsageAccumulator {

    long individualUsageData;
    long sharedUsageData;
    long qosUsageData;
}
