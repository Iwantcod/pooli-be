package com.pooli.monitoring.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.batch.LineDailyBatchJob;
import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.service.batch.LineDailyBatchJobService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 일별 usage sync batch의 운영 관측값을 Micrometer Gauge로 노출한다.
 * <p>
 * Prometheus/AlertManager는 이 클래스가 등록한 gauge를 scrape해
 * 실패 target 발생과 06:00 KST deadline 초과 여부를 판단한다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageSyncBatchMetrics {

    private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Seoul");

    private final MeterRegistry meterRegistry;
    private final LineDailyBatchJobService lineDailyBatchJobService;
    private final Clock clock;

    private final AtomicLong failedCount = new AtomicLong(0L);
    private final AtomicLong statusCode = new AtomicLong(0L);
    private final AtomicLong runDurationSeconds = new AtomicLong(0L);

    /**
     * Prometheus가 scrape할 gauge 3개를 등록하고, 시작 직후 한 번 현재 DB 상태를 반영한다.
     */
    @PostConstruct
    void init() {
        // 1. 최신 usage sync batch의 failed_count를 실패 알림 조건으로 등록한다.
        Gauge.builder("batch_daily_usage_sync_failed_count", failedCount, AtomicLong::get)
                .description("Failed target count of the latest daily usage sync batch")
                .register(meterRegistry);
        // 2. 최신 usage sync batch 상태를 숫자 코드로 노출해 운영 대시보드에서 상태를 구분한다.
        Gauge.builder("batch_daily_usage_sync_status", statusCode, AtomicLong::get)
                .description("Latest daily usage sync batch status code: none=0,pending=1,running=2,completed=3,failed=4,abandoned=5")
                .register(meterRegistry);
        // 3. RUNNING 상태의 경과 시간을 deadline 초과 알림 조건으로 등록한다.
        Gauge.builder("batch_daily_usage_sync_run_duration_seconds", runDurationSeconds, AtomicLong::get)
                .description("Elapsed seconds of the latest RUNNING daily usage sync batch")
                .register(meterRegistry);
        // 4. 첫 scrape 전에 gauge가 의미 있는 초기값을 갖도록 즉시 한 번 갱신한다.
        refresh();
    }

    /**
     * DB의 최신 usage sync batch metadata를 읽어 gauge holder 값을 갱신한다.
     * RUNNING batch가 아닐 때는 실패 수를 최신 metadata 기준으로 보존하고 경과 시간만 0으로 내린다.
     * 기본 30초 주기는 Spring 앱 내부의 DB 조회/holder 갱신 주기이며,
     * Prometheus scrape 주기와 AlertManager 평가/알림 주기는 인프라 설정을 따른다.
     */
    @Scheduled(fixedDelayString = "${app.traffic.daily-batch.metrics-refresh-ms:30000}")
    void refresh() {
        // 1. usage sync batch 중 가장 최근 row를 조회한다.
        LineDailyBatchJob batchJob = lineDailyBatchJobService.findLatestUsageSyncBatch();
        if (batchJob == null) {
            // 2. 아직 batch row가 없으면 모든 관측값을 기본값으로 둔다.
            updateEmpty();
            return;
        }

        // 3. 최신 batch 상태 코드는 RUNNING 여부와 무관하게 항상 반영한다.
        statusCode.set(statusCode(batchJob.getStatus()));
        failedCount.set(nonNullCount(batchJob.getFailedCount()));
        if (batchJob.getStatus() != LineDailyBatchStatus.RUNNING) {
            // 4. terminal 또는 pending batch는 현재 진행 중인 경과 시간 알림 대상이 아니다.
            runDurationSeconds.set(0L);
            return;
        }

        // 5. RUNNING batch는 실행 경과 시간을 deadline 알림용 gauge로 노출한다.
        runDurationSeconds.set(runDurationSeconds(batchJob.getRunStartedAt()));
    }

    /**
     * 관측할 batch row가 없을 때 gauge 값을 모두 기본값으로 초기화한다.
     */
    private void updateEmpty() {
        // 1. 이전 scrape에서 남은 값이 alert로 오인되지 않도록 모든 holder를 0으로 둔다.
        failedCount.set(0L);
        statusCode.set(0L);
        runDurationSeconds.set(0L);
    }

    /**
     * batch 상태 enum을 Prometheus gauge에 실을 숫자 코드로 변환한다.
     */
    private long statusCode(LineDailyBatchStatus status) {
        // 1. 상태가 없다는 의미는 none 코드 0으로 표현한다.
        if (status == null) {
            return 0L;
        }
        // 2. enum ordinal에 의존하지 않고 운영 문서에 고정한 코드값을 명시적으로 반환한다.
        return switch (status) {
            case PENDING -> 1L;
            case RUNNING -> 2L;
            case COMPLETED -> 3L;
            case FAILED -> 4L;
            case ABANDONED -> 5L;
        };
    }

    /**
     * RUNNING batch의 시작 시각부터 현재 clock까지의 KST 기준 경과 초를 계산한다.
     */
    private long runDurationSeconds(LocalDateTime runStartedAt) {
        // 1. 시작 시각이 비어 있으면 deadline 판단에 사용할 수 없으므로 0으로 둔다.
        if (runStartedAt == null) {
            return 0L;
        }
        // 2. DB LocalDateTime을 배치 기준 timezone인 KST instant로 해석해 현재 clock과 비교한다.
        long seconds = Duration.between(
                runStartedAt.atZone(BATCH_ZONE).toInstant(),
                clock.instant()
        ).getSeconds();
        // 3. clock skew나 테스트 clock 차이로 음수가 나오면 gauge에는 0을 노출한다.
        return Math.max(0L, seconds);
    }

    /**
     * DB count 값이 null일 때 gauge에 안전한 기본값을 제공한다.
     */
    private long nonNullCount(Long count) {
        // 1. metadata count가 비어 있는 row도 Prometheus에는 숫자형 gauge로 노출해야 한다.
        return count == null ? 0L : count;
    }
}
