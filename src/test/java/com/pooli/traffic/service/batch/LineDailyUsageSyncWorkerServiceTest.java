package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageSyncWorkerServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;

    @Mock
    private LineDailyUsageRedisReader lineDailyUsageRedisReader;

    @Mock
    private LineDailyUsageSyncPersistenceService lineDailyUsageSyncPersistenceService;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    @Mock
    private LineDailyBatchJobService lineDailyBatchJobService;

    @InjectMocks
    private LineDailyUsageSyncWorkerService lineDailyUsageSyncWorkerService;

    @Test
    @DisplayName("worker loop 시작 시 같은 usage_date의 target row 한 chunk를 선점한다")
    void claimsTargetChunkForUsageDateWhenWorkerStarts() {
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of());

        LineDailyUsageSyncWorkerRunResult result = lineDailyUsageSyncWorkerService.run(batchJob);

        assertEquals(LineDailyUsageSyncWorkerRunResult.STOP, result);
        verify(lineDailyBatchTargetClaimService).claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        );
    }

    @Test
    @DisplayName("선점한 target row별 Redis 사용량 snapshot을 조회한다")
    void readsRedisUsageSnapshotForClaimedTargets() {
        LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
        LineDailyBatchTarget target = LineDailyBatchTarget.builder()
                .id(10L)
                .usageDate(USAGE_DATE)
                .lineId(11L)
                .build();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(target));
        when(lineDailyUsageRedisReader.read(target))
                .thenReturn(new LineDailyUsageReadResult(null, List.of(), null));

        LineDailyUsageSyncWorkerRunResult result = lineDailyUsageSyncWorkerService.run(batchJob);

        assertEquals(LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY, result);
        verify(lineDailyUsageRedisReader).read(target);
        verify(lineDailyUsageSyncPersistenceService).persistUsageAndCompleteTarget(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(target),
                org.mockito.ArgumentMatchers.any(LineDailyUsageReadResult.class),
                anyString()
        );
    }

    @Test
    @DisplayName("여러 worker cycle은 각자 선점한 target row만 처리한다")
    void multipleWorkerCyclesProcessOnlyTheirClaimedTargets() {
        LineDailyBatchJob batchJob = batchJob();
        LineDailyBatchTarget firstTarget = target().toBuilder()
                .id(10L)
                .lineId(101L)
                .build();
        LineDailyBatchTarget secondTarget = target().toBuilder()
                .id(11L)
                .lineId(102L)
                .build();
        LineDailyUsageReadResult firstSnapshot = new LineDailyUsageReadResult(100L, List.of(), null);
        LineDailyUsageReadResult secondSnapshot = new LineDailyUsageReadResult(200L, List.of(), null);
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(firstTarget), List.of(secondTarget));
        when(lineDailyUsageRedisReader.read(firstTarget)).thenReturn(firstSnapshot);
        when(lineDailyUsageRedisReader.read(secondTarget)).thenReturn(secondSnapshot);

        LineDailyUsageSyncWorkerRunResult firstResult = lineDailyUsageSyncWorkerService.run(batchJob);
        LineDailyUsageSyncWorkerRunResult secondResult = lineDailyUsageSyncWorkerService.run(batchJob);

        assertEquals(LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY, firstResult);
        assertEquals(LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY, secondResult);
        ArgumentCaptor<String> workerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(lineDailyUsageSyncPersistenceService).persistUsageAndCompleteTarget(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(firstTarget),
                org.mockito.ArgumentMatchers.eq(firstSnapshot),
                workerIdCaptor.capture()
        );
        verify(lineDailyUsageSyncPersistenceService).persistUsageAndCompleteTarget(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(secondTarget),
                org.mockito.ArgumentMatchers.eq(secondSnapshot),
                workerIdCaptor.capture()
        );
        verify(lineDailyUsageRedisReader, times(1)).read(firstTarget);
        verify(lineDailyUsageRedisReader, times(1)).read(secondTarget);
        org.assertj.core.api.Assertions.assertThat(workerIdCaptor.getAllValues())
                .allMatch(workerId -> workerId.startsWith("line-daily-worker:" + USAGE_DATE + ":"))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("재시도 가능한 처리 실패는 target row를 RETRYABLE 실패로 기록한다")
    void recordsRetryableFailureWhenTargetProcessingThrowsRetryableException() {
        LineDailyBatchJob batchJob = batchJob();
        LineDailyBatchTarget target = target();
        QueryTimeoutException exception = new QueryTimeoutException("timeout");
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(target));
        when(lineDailyUsageRedisReader.read(target)).thenThrow(exception);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)).thenReturn(false);

        lineDailyUsageSyncWorkerService.run(batchJob);

        verify(lineDailyUsageSyncPersistenceService).recordRetryableFailure(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(target),
                anyString(),
                org.mockito.ArgumentMatchers.eq("RETRYABLE_WORKER_FAILURE"),
                org.mockito.ArgumentMatchers.contains("timeout")
        );
        verify(lineDailyUsageSyncPersistenceService, never()).recordNonRetryableFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("자동 복구가 불가능한 처리 실패는 target row를 FAILED 실패로 기록한다")
    void recordsNonRetryableFailureWhenTargetProcessingThrowsNonRetryableException() {
        LineDailyBatchJob batchJob = batchJob();
        LineDailyBatchTarget target = target();
        IllegalStateException exception = new IllegalStateException("invalid redis field");
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(target));
        when(lineDailyUsageRedisReader.read(target)).thenThrow(exception);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)).thenReturn(false);

        lineDailyUsageSyncWorkerService.run(batchJob);

        verify(lineDailyUsageSyncPersistenceService).recordNonRetryableFailure(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(target),
                anyString(),
                org.mockito.ArgumentMatchers.eq("NON_RETRYABLE_WORKER_FAILURE"),
                org.mockito.ArgumentMatchers.contains("invalid redis field")
        );
        verify(lineDailyUsageSyncPersistenceService, never()).recordRetryableFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("DB 예외 cause chain이 순환해도 재시도 판단을 종료하고 FAILED로 기록한다")
    void recordsNonRetryableFailureWhenDataAccessExceptionCauseChainCycles() {
        LineDailyBatchJob batchJob = batchJob();
        LineDailyBatchTarget target = target();
        SelfCausingDataAccessException exception = new SelfCausingDataAccessException();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of(target));
        when(lineDailyUsageRedisReader.read(target)).thenThrow(exception);
        when(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(exception)).thenReturn(false);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> lineDailyUsageSyncWorkerService.run(batchJob));

        verify(lineDailyUsageSyncPersistenceService).recordNonRetryableFailure(
                org.mockito.ArgumentMatchers.eq(batchJob.getId()),
                org.mockito.ArgumentMatchers.eq(target),
                anyString(),
                org.mockito.ArgumentMatchers.eq("NON_RETRYABLE_WORKER_FAILURE"),
                org.mockito.ArgumentMatchers.contains("self-causing")
        );
        verify(lineDailyUsageSyncPersistenceService, never()).recordRetryableFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("선점 row가 없고 non-terminal row가 남아 있으면 1분 empty poll 재예약을 요청한다")
    void requestsEmptyPollWhenNoClaimedTargetButNonTerminalTargetsRemain() {
        LineDailyBatchJob batchJob = batchJob();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of());
        when(lineDailyBatchTargetMapper.countNonTerminalByUsageDate(USAGE_DATE)).thenReturn(2L);

        LineDailyUsageSyncWorkerRunResult result = lineDailyUsageSyncWorkerService.run(batchJob);

        assertEquals(LineDailyUsageSyncWorkerRunResult.WAIT_FOR_EMPTY_POLL, result);
        verify(lineDailyBatchJobService, never()).completeRunningUsageSyncBatchIfCountsMatch(batchJob);
    }

    @Test
    @DisplayName("선점 row와 non-terminal row가 없으면 usage sync 완료 CAS를 시도한다")
    void completesUsageSyncBatchWhenNoClaimedTargetAndNoNonTerminalTargetsRemain() {
        LineDailyBatchJob batchJob = batchJob();
        when(lineDailyBatchTargetClaimService.claim(
                org.mockito.ArgumentMatchers.eq(USAGE_DATE),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LineDailyUsageSyncWorkerService.WORKER_CLAIM_CHUNK_SIZE)
        )).thenReturn(List.of());
        when(lineDailyBatchTargetMapper.countNonTerminalByUsageDate(USAGE_DATE)).thenReturn(0L);
        when(lineDailyBatchJobService.completeRunningUsageSyncBatchIfCountsMatch(batchJob)).thenReturn(true);

        LineDailyUsageSyncWorkerRunResult result = lineDailyUsageSyncWorkerService.run(batchJob);

        assertEquals(LineDailyUsageSyncWorkerRunResult.STOP, result);
        verify(lineDailyBatchJobService).completeRunningUsageSyncBatchIfCountsMatch(batchJob);
    }

    private LineDailyBatchJob batchJob() {
        return LineDailyBatchJob.builder()
                .id(2L)
                .batchName(BatchName.LINE_DAILY_USAGE_SYNC_BATCH)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .build();
    }

    private LineDailyBatchTarget target() {
        return LineDailyBatchTarget.builder()
                .id(10L)
                .usageDate(USAGE_DATE)
                .lineId(11L)
                .build();
    }

    /**
     * cause가 자기 자신을 가리키는 비정상 DB 예외를 만들어 cause chain 순환 방어를 검증한다.
     */
    private static final class SelfCausingDataAccessException extends DataAccessException {

        /**
         * 테스트용 self-cause DB 예외 메시지를 초기화한다.
         */
        private SelfCausingDataAccessException() {
            super("self-causing");
        }

        /**
         * cause chain 순환 상황을 재현하기 위해 자기 자신을 cause로 반환한다.
         */
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
