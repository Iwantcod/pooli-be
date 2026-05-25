package com.pooli.traffic.domain.batch;

/**
 * 자동 배치 metadata 생성 요청의 결과이다.
 * created=false이면 같은 batch_name + usage_date 기존 row를 반환했다는 의미이다.
 */
public record LineDailyBatchJobCreateResult(
        boolean created,
        LineDailyBatchJob batchJob
) {
}
