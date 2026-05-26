package com.pooli.traffic.service.batch;

/**
 * FAMILY_SHARED_USAGE_DAILY insert에 필요한 공유풀 일별 사용량 값이다.
 */
record DailySharedUsage(
        Long familyId,
        Long usageAmount
) {
}
