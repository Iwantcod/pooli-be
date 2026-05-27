package com.pooli.traffic.service.batch;

import java.util.List;

/**
 * 한 target row에 대응하는 Redis 일별 사용량 조회 결과이다.
 *
 * <p>세 값이 모두 비어 있으면 후속 단계에서 DB insert 없이 target row를 SKIPPED로 전환한다.
 * 하나라도 있으면 존재하는 사용량만 insert하고 target row를 DONE으로 전환할 수 있다.
 *
 * @param totalUsageData 회선의 일별 총 트래픽 사용량(단위: byte)으로, Redis의 daily total 키에서 집계된 단일 누적값이다.
 * @param appUsages 회선이 사용한 개별 애플리케이션 단위의 트래픽 사용량 목록으로, Redis의 daily app 키들에서 조회된 각 애플리케이션별 데이터(개별/공유/QoS 사용량 포함)를 의미한다.
 * @param sharedUsage 회선이 속한 가족/그룹 단위의 공유 데이터 풀에서 당일 소진한 트래픽 사용량으로, Redis의 shared pool daily usage 키에서 집계된 데이터이다.
 */
record LineDailyUsageReadResult(
        Long totalUsageData,
        List<DailyAppUsage> appUsages,
        DailySharedUsage sharedUsage
) {

    boolean hasAnyUsage() {
        return totalUsageData != null || !appUsages.isEmpty() || sharedUsage != null;
    }
}
