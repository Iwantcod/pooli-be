package com.pooli.traffic.domain.dto.response;

import java.time.LocalDate;

import com.pooli.traffic.domain.batch.LineDailyBatchStatus;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 관리자 usage sync 재개 요청의 접수 결과입니다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineDailyUsageSyncResumeResDto {

    /** 재개 대상이 되는 일별 트래픽 동기화 배치 작업의 고유 식별자입니다. */
    private final Long batchJobId;

    /** 동기화 재개를 요청한 기준 일자(YYYY-MM-DD)입니다. */
    private final LocalDate usageDate;

    /** 현재 해당 일별 배치의 상태(예: PENDING, IN_PROGRESS, FAILED 등)를 나타냅니다. */
    private final LineDailyBatchStatus status;

    /** 재개 요청이 성공적으로 접수되어 처리가 시작될 수 있는지 여부를 나타냅니다. */
    private final boolean resumeAccepted;
}
