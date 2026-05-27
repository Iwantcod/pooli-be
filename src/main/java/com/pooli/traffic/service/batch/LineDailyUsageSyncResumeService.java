package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncResumeResDto;

import lombok.RequiredArgsConstructor;

/**
 * 운영자가 명시한 usage_date의 usage sync worker 재개 요청을 처리합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncResumeService {

    private final LineDailyBatchJobService lineDailyBatchJobService;
    private final LineDailyBatchWorkerScheduler lineDailyBatchWorkerScheduler;

    public LineDailyUsageSyncResumeResDto resume(LocalDate usageDate) {
        LineDailyBatchJob batchJob = lineDailyBatchJobService.findLatestUsageSyncBatch(usageDate);
        if (batchJob == null || batchJob.getStatus() != LineDailyBatchStatus.RUNNING) {
            return response(batchJob, usageDate, false);
        }

        lineDailyBatchWorkerScheduler.startForUsageDate(usageDate);
        return response(batchJob, usageDate, true);
    }

    private LineDailyUsageSyncResumeResDto response(
            LineDailyBatchJob batchJob,
            LocalDate usageDate,
            boolean resumeAccepted
    ) {
        return LineDailyUsageSyncResumeResDto.builder()
                .batchJobId(batchJob == null ? null : batchJob.getId())
                .usageDate(usageDate)
                .status(batchJob == null ? null : batchJob.getStatus())
                .resumeAccepted(resumeAccepted)
                .build();
    }
}
