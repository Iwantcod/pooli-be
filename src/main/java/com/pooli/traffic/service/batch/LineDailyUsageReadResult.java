package com.pooli.traffic.service.batch;

import java.util.List;

/**
 * 한 target row에 대응하는 Redis 일별 사용량 조회 결과이다.
 *
 * <p>세 값이 모두 비어 있으면 후속 단계에서 DB insert 없이 target row를 SKIPPED로 전환한다.
 * 하나라도 있으면 존재하는 사용량만 insert하고 target row를 DONE으로 전환할 수 있다.
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
