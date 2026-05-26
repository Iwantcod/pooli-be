package com.pooli.traffic.service.batch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Usage sync batch가 RUNNING으로 열린 뒤에만 worker loop를 시작한다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyBatchWorkerScheduler {

    static final long START_CHECK_DELAY_MS = 1000L;
    static final long START_CHECK_JITTER_BOUND_MS = 250L;
    static final long EMPTY_POLL_DELAY_MS = 60_000L;

    private final LineDailyBatchJobService lineDailyBatchJobService;
    private final LineDailyUsageSyncWorkerService lineDailyUsageSyncWorkerService;
    private final TaskScheduler taskScheduler;

    private volatile boolean stopped;

    /**
     * manager lock 획득에 실패한 서버가 같은 usageDate의 worker 시작 감지를 시작한다.
     * 1. 첫 확인은 지연 없이 예약한다.
     * 2. 실제 조회와 재예약 판단은 scheduler thread에서 실행되는 cycle 메서드에 위임한다.
     */
    public void startForUsageDate(LocalDate usageDate) {
        scheduleNextCheck(usageDate, 0L);
    }

    /**
     * 애플리케이션 종료 시 더 이상 새 시작 감지 작업을 예약하지 않도록 표시한다.
     * 이미 Spring TaskScheduler에 등록된 작업이 늦게 실행되더라도 stopped 값을 보고 즉시 종료한다.
     */
    @PreDestroy
    public void stop() {
        stopped = true;
    }

    /**
     * usage sync worker를 시작할 수 있는지 확인하는 단일 cycle이다.
     * 1. 종료 중이면 추가 작업 없이 반환한다.
     * 2. RUNNING usage sync batch가 없으면 1초와 jitter를 더해 다음 확인을 예약한다.
     * 3. RUNNING batch가 있으면 worker 진입점으로 넘기고 시작 감지 재예약은 중단한다.
     */
    void runStartCheckCycle(LocalDate usageDate) {
        // 1. 종료 이후 지연 실행된 task가 worker를 깨우지 않도록 방어한다.
        if (stopped) {
            return;
        }

        // 2. manager가 같은 usageDate의 usage sync batch를 RUNNING으로 열었는지 확인한다.
        LineDailyBatchJob runningBatch = lineDailyBatchJobService.findRunningUsageSyncBatch(usageDate);
        if (runningBatch == null) {
            // 3. 아직 시작 조건이 아니면 thread 점유 없이 다음 확인만 예약한다.
            scheduleNextCheck(usageDate, START_CHECK_DELAY_MS + nextJitterMs());
            return;
        }

        // 4. 시작 조건이 충족되면 worker service를 한 cycle 실행하고 결과에 따라 다음 cycle을 예약한다.
        LineDailyUsageSyncWorkerRunResult result = lineDailyUsageSyncWorkerService.run(runningBatch);
        if (result == LineDailyUsageSyncWorkerRunResult.CONTINUE_IMMEDIATELY) {
            scheduleNextCheck(usageDate, 0L);
        } else if (result == LineDailyUsageSyncWorkerRunResult.WAIT_FOR_EMPTY_POLL) {
            scheduleNextCheck(usageDate, EMPTY_POLL_DELAY_MS);
        }
    }

    /**
     * 다음 시작 감지 cycle을 Spring TaskScheduler에 등록한다.
     * delayMs가 0이면 즉시 실행 예약이고, 양수이면 현재 시각 기준 지연 예약이다.
     */
    private void scheduleNextCheck(LocalDate usageDate, long delayMs) {
        if (stopped) {
            return;
        }

        taskScheduler.schedule(() -> runStartCheckCycle(usageDate), Instant.now().plusMillis(delayMs));
    }

    /**
     * 여러 서버 worker가 같은 시점에 metadata를 재조회하지 않도록 짧은 무작위 지연을 만든다.
     */
    private long nextJitterMs() {
        return ThreadLocalRandom.current().nextLong(START_CHECK_JITTER_BOUND_MS + 1);
    }
}
