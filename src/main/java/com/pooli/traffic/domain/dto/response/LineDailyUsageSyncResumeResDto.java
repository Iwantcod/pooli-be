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

    private final Long batchJobId;
    private final LocalDate usageDate;
    private final LineDailyBatchStatus status;
    private final boolean resumeAccepted;
}
