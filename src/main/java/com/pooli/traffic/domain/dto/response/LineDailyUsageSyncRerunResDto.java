package com.pooli.traffic.domain.dto.response;

import java.time.LocalDate;

import com.pooli.traffic.domain.batch.LineDailyBatchStatus;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 관리자 usage sync rerun 요청의 접수 결과입니다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineDailyUsageSyncRerunResDto {

    /** rerun 요청의 기준이 된 직전 usage sync batch id */
    private final Long previousBatchJobId;

    /** rerun 요청이 승인된 경우 새로 생성된 usage sync batch id */
    private final Long rerunBatchJobId;

    /** rerun 대상 사용량 기준 일자 */
    private final LocalDate usageDate;

    /** rerun 가능 여부를 판단할 때 확인한 직전 batch 상태 */
    private final LineDailyBatchStatus previousStatus;

    /** 새 rerun batch가 처리할 FAILED target row 수 */
    private final Long targetCount;

    /** rerun 요청이 실제 worker 시작 대상으로 접수되었는지 여부 */
    private final boolean rerunAccepted;
}
