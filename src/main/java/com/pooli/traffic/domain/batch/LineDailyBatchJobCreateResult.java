package com.pooli.traffic.domain.batch;

/**
 * 자동 배치 metadata 생성 요청 결과이다.
 *
 * @param created 신규 {@link LineDailyBatchJob}이 생성되었으면 {@code true},
 *                같은 batch_name + usage_date의 기존 row를 재사용했으면 {@code false}
 * @param batchJob 생성 결과에 대응되는 {@link LineDailyBatchJob}이다.
 *                 {@code created == true}이면 새로 생성된 job이고,
 *                 {@code created == false}이면 재사용된 기존 job이다.
 *                 자동 배치 metadata 생성 서비스의 반환 계약상 {@code null}이 아니다.
 */
public record LineDailyBatchJobCreateResult(
        boolean created,
        LineDailyBatchJob batchJob
) {
}
