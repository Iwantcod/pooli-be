package com.pooli.traffic.service.decision;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.domain.entity.TrafficRedisUsageDeltaRecord;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB fallback 동안 누적된 usage delta를 Redis로 재반영하는 스케줄러입니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficUsageDeltaReplayScheduler {

    private static final long REPLAY_IDEMPOTENCY_TTL_SECONDS = 90L * 24L * 60L * 60L;

    private static final DefaultRedisScript<Long> USAGE_REPLAY_SCRIPT = createUsageReplayScript();

    @Value("${app.traffic.redis-usage-replay.batch-size:200}")
    private int batchSize;

    @Value("${app.traffic.redis-usage-replay.max-retry-count:20}")
    private int maxRetryCount;

    @Value("${app.traffic.redis-usage-replay.processing-stuck-seconds:180}")
    private int processingStuckSeconds;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficUsageDeltaRecordService trafficUsageDeltaRecordService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;

    /**
     * Redis usage delta replay 주기를 실행합니다.
     */
    @Scheduled(fixedDelayString = "${app.traffic.redis-usage-replay.fixed-delay-ms:5000}")
    public void runReplayCycle() {
        List<TrafficRedisUsageDeltaRecord> candidates = trafficUsageDeltaRecordService.lockReplayCandidatesAndMarkProcessing(
                Math.max(1, batchSize),
                Math.max(1, processingStuckSeconds)
        );

        for (TrafficRedisUsageDeltaRecord candidate : candidates) {
            if (candidate.getId() == null) {
                continue;
            }

            int retryCount = candidate.getRetryCount() == null ? 0 : candidate.getRetryCount();
            if (retryCount >= Math.max(1, maxRetryCount)) {
                trafficUsageDeltaRecordService.markFailWithRetryCount(
                        candidate.getId(),
                        retryCount,
                        "max retry exceeded"
                );
                trafficDeductFallbackMetrics.incrementReplayResult("max_retry_exceeded");
                continue;
            }

            try {
                replayOne(candidate);
                trafficUsageDeltaRecordService.markSuccess(candidate.getId());
                trafficDeductFallbackMetrics.incrementReplayResult("success");
            } catch (RuntimeException e) {
                log.error(
                        "traffic_usage_delta_replay_failed id={} traceId={} poolType={}",
                        candidate.getId(),
                        candidate.getTraceId(),
                        candidate.getPoolType(),
                        e
                );
                trafficUsageDeltaRecordService.markFailWithRetryIncrement(candidate.getId(), e.getMessage());
                trafficDeductFallbackMetrics.incrementReplayResult("fail");
            }
        }

        trafficDeductFallbackMetrics.updateReplayBacklog(trafficUsageDeltaRecordService.countBacklog());
    }

    /**
     * usage delta 1건을 Redis에 멱등 반영합니다.
     */
    private void replayOne(TrafficRedisUsageDeltaRecord record) {
        if (record.getTraceId() == null
                || record.getPoolType() == null
                || record.getLineId() == null
                || record.getLineId() <= 0
                || record.getAppId() == null
                || record.getAppId() < 0
                || record.getUsedBytes() == null
                || record.getUsedBytes() <= 0
                || record.getUsageDate() == null
                || record.getTargetMonth() == null
                || record.getTargetMonth().isBlank()) {
            throw new IllegalStateException("usage delta record is not valid");
        }

        LocalDate usageDate = record.getUsageDate();
        YearMonth targetMonth = YearMonth.parse(record.getTargetMonth());

        String idempotencyKey = trafficRedisKeyFactory.usageDeltaReplayIdempotencyKey(
                record.getTraceId(),
                record.getPoolType().name()
        );
        String dailyTotalUsageKey = trafficRedisKeyFactory.dailyTotalUsageKey(record.getLineId(), usageDate);
        String dailyAppUsageKey = trafficRedisKeyFactory.dailyAppUsageKey(record.getLineId(), usageDate);
        String monthlySharedUsageKey = resolveMonthlySharedUsageKey(record, targetMonth);

        long dailyExpireAtEpochSeconds = trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(usageDate);
        long monthlyExpireAtEpochSeconds = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth);

        Long scriptResult = cacheStringRedisTemplate.execute(
                USAGE_REPLAY_SCRIPT,
                List.of(idempotencyKey, dailyTotalUsageKey, dailyAppUsageKey, monthlySharedUsageKey),
                String.valueOf(record.getUsedBytes()),
                "app:" + record.getAppId(),
                String.valueOf(dailyExpireAtEpochSeconds),
                String.valueOf(monthlyExpireAtEpochSeconds),
                String.valueOf(record.getPoolType() == TrafficPoolType.SHARED ? 1 : 0),
                String.valueOf(REPLAY_IDEMPOTENCY_TTL_SECONDS)
        );

        if (scriptResult == null) {
            throw new IllegalStateException("usage replay lua returned null");
        }
    }

    /**
     * shared pool에서만 월 사용량 키를 구성합니다.
     */
    private String resolveMonthlySharedUsageKey(TrafficRedisUsageDeltaRecord record, YearMonth targetMonth) {
        if (record.getPoolType() != TrafficPoolType.SHARED) {
            return "";
        }
        return trafficRedisKeyFactory.monthlySharedUsageKey(record.getLineId(), targetMonth);
    }

    /**
     * usage delta를 멱등 반영하는 Lua 스크립트를 구성합니다.
     */
    private static DefaultRedisScript<Long> createUsageReplayScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('SETNX', KEYS[1], '1') == 0 then
                  return 0
                end

                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[6]))

                redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))
                redis.call('EXPIREAT', KEYS[2], tonumber(ARGV[3]))

                redis.call('HINCRBY', KEYS[3], ARGV[2], tonumber(ARGV[1]))
                redis.call('EXPIREAT', KEYS[3], tonumber(ARGV[3]))

                if tonumber(ARGV[5]) == 1 and KEYS[4] ~= '' then
                  redis.call('INCRBY', KEYS[4], tonumber(ARGV[1]))
                  redis.call('EXPIREAT', KEYS[4], tonumber(ARGV[4]))
                end

                return 1
                """);
        return script;
    }
}
