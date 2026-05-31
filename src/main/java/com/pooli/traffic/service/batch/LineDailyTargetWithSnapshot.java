package com.pooli.traffic.service.batch;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;

/**
 * Redis 조회가 성공한 target row와 해당 Redis snapshot을 함께 전달하는 묶음 값입니다.
 *
 * @param target worker가 PROCESSING으로 선점했고 DB 영속화 후 terminal 상태로 닫아야 하는 target row입니다.
 * @param snapshot Redis에서 읽은 일별 사용량 snapshot으로, 사용량 존재 여부에 따라 DONE 또는 SKIPPED 분류 기준이 됩니다.
 */
record LineDailyTargetWithSnapshot(
        LineDailyBatchTarget target,
        LineDailyUsageReadResult snapshot
) {
}
