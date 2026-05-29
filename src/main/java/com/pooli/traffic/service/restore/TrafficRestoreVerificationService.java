package com.pooli.traffic.service.restore;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.RestoreRange;
import com.pooli.traffic.domain.restore.RestoreVerificationResult;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationLineRange;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationTarget;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficRestoreVerificationMapper;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 복구 replay 완료 후 RDB 기준값과 Redis 값을 전체 비교하고 불일치를 보정한다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreVerificationService {

    private static final String CORRECTION_FAILED_CODE = "RESTORE_CORRECTION_FAILED";
    private static final long VERIFICATION_LINE_CHUNK_SIZE = 1000L;

    private final TrafficRestoreVerificationMapper verificationMapper;
    private final LineDailyBatchJobMapper batchJobMapper;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRestoreIdempotencyCleanupService idempotencyCleanupService;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * 복구 대상 Redis hash field를 sampling 없이 검증하고 기준값으로 자동 보정한다.
     */
    public RestoreVerificationResult verifyAndCorrect(LocalDate anchorDate, RestoreRange restoreRange) {
        return verifyAndCorrect(anchorDate, restoreRange, null);
    }

    /**
     * 보정 실패 시 지정된 phase 2 metadata row를 FAILED로 전환하면서 전체 검증을 수행한다.
     */
    public RestoreVerificationResult verifyAndCorrect(
            LocalDate anchorDate,
            RestoreRange restoreRange,
            Long phase2BatchJobId
    ) {
        // 1. 전체 원천 row를 한 번에 적재하지 않고 검증 대상 line_id 최소/최대 범위만 먼저 조회한다.
        TrafficRestoreVerificationLineRange lineRange = verificationMapper.selectVerificationLineRange(
                restoreRange.startInclusive(),
                restoreRange.endExclusive(),
                restoreRange.startDateTimeInclusive(),
                restoreRange.endDateTimeExclusive()
        );

        VerificationCounters counters = new VerificationCounters();
        if (lineRange != null && lineRange.exists()) {
            // 2. 월별 잔량과 월별 공유 사용량은 전체 복구 기간 기준으로 line range별로 검증한다.
            forEachLineChunk(lineRange, (lineIdStartInclusive, lineIdEndInclusive) ->
                    verifyTargets(
                            verificationMapper.selectRemainingVerificationTargets(
                                    restoreRange.startInclusive(),
                                    restoreRange.endExclusive(),
                                    restoreRange.startDateTimeInclusive(),
                                    restoreRange.endDateTimeExclusive(),
                                    lineIdStartInclusive,
                                    lineIdEndInclusive
                            ),
                            anchorDate,
                            counters
                    )
            );

            // 3. 일별 사용량 key는 하루 단위로 더 쪼개 line range별 검증 target을 산출한다.
            LocalDate usageDate = restoreRange.startInclusive();
            while (usageDate.isBefore(restoreRange.endExclusive())) {
                LocalDate currentUsageDate = usageDate;
                forEachLineChunk(lineRange, (lineIdStartInclusive, lineIdEndInclusive) ->
                        verifyTargets(
                                verificationMapper.selectUsageVerificationTargets(
                                        currentUsageDate,
                                        currentUsageDate.atStartOfDay(),
                                        currentUsageDate.plusDays(1).atStartOfDay(),
                                        lineIdStartInclusive,
                                        lineIdEndInclusive
                                ),
                                anchorDate,
                                counters
                        )
                );
                usageDate = usageDate.plusDays(1);
            }
        }

        // 4. 정책 key는 원천 row 수가 작아 line range와 무관하게 별도 검증한다.
        verifyTargets(verificationMapper.selectPolicyVerificationTargets(), anchorDate, counters);

        // 5. 보정 실패가 전혀 없을 때만 replay idempotency key 잔여분을 cleanup한다.
        long cleanedCount = counters.failedCorrectionCount == 0L
                ? idempotencyCleanupService.cleanupRestoreIdempotencyKeys()
                : 0L;
        // 6. failedCorrectionCount > 0이면 적어도 하나의 field 보정이 실패한 것이므로 phase 2 metadata를 FAILED로 닫는다.
        if (counters.failedCorrectionCount > 0L && phase2BatchJobId != null) {
            batchJobMapper.failRunningRestorePhaseBatch(
                    phase2BatchJobId,
                    BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                    CORRECTION_FAILED_CODE,
                    "Redis 복구 검증 보정 실패 count=" + counters.failedCorrectionCount
            );
        }
        return new RestoreVerificationResult(
                counters.matchedCount,
                counters.correctedCount,
                counters.failedCorrectionCount,
                cleanedCount
        );
    }

    private void verifyTargets(
            List<TrafficRestoreVerificationTarget> targets,
            LocalDate anchorDate,
            VerificationCounters counters
    ) {
        for (TrafficRestoreVerificationTarget target : targets) {
            // 1. target 유형과 식별자를 실제 Redis key/field로 변환한 뒤 현재 값을 읽는다.
            String key = resolveRedisKey(target);
            String field = target.getField();
            long expectedValue = nullToZero(target.getExpectedValue());
            long actualValue = readHashLong(key, field);
            if (actualValue == expectedValue) {
                counters.matchedCount++;
                continue;
            }

            log.warn(
                    "traffic_restore_verification_mismatch anchorDate={} key={} field={} expected={} actual={}",
                    anchorDate,
                    key,
                    field,
                    expectedValue,
                    actualValue
            );
            // 2. 불일치 field는 replay Lua와 분리된 correction Lua로 기준값을 원자 반영한다.
            //    실패 판정식은 correct(...) == false이며, Lua 결과가 CORRECTED가 아니거나 Redis 예외가 난 경우이다.
            if (correct(key, field, expectedValue, nullToZero(target.getExpireEpochSeconds()))) {
                counters.correctedCount++;
            } else {
                counters.failedCorrectionCount++;
            }
        }
    }

    private void forEachLineChunk(TrafficRestoreVerificationLineRange lineRange, LineChunkConsumer consumer) {
        long maxLineId = lineRange.getMaxLineId();
        long lineIdStartInclusive = lineRange.getMinLineId();
        while (lineIdStartInclusive <= maxLineId) {
            long lineIdEndInclusive = Math.min(
                    maxLineId,
                    lineIdStartInclusive + VERIFICATION_LINE_CHUNK_SIZE - 1L
            );
            consumer.accept(lineIdStartInclusive, lineIdEndInclusive);
            lineIdStartInclusive = lineIdEndInclusive + 1L;
        }
    }

    private boolean correct(String key, String field, long expectedValue, long expireEpochSeconds) {
        try {
            // 1. 보정 Lua는 단일 hash field와 TTL만 변경해 replay 로직과 책임을 분리한다.
            List<String> result = trafficLuaScriptInfraService.executeRestoreUsageCorrection(
                    key,
                    field,
                    expectedValue,
                    expireEpochSeconds
            );
            // 2. Lua가 명시적으로 CORRECTED를 반환한 경우만 보정 성공으로 집계한다.
            return !result.isEmpty() && "CORRECTED".equals(result.get(0));
        } catch (RuntimeException e) {
            // 3. Redis 보정 예외는 전체 검증을 중단하지 않고 실패 count로 전환한다.
            log.error("traffic_restore_correction_failed key={} field={}", key, field, e);
            return false;
        }
    }

    private String resolveRedisKey(TrafficRestoreVerificationTarget target) {
        // 검증 target 유형별로 운영 코드가 쓰는 TrafficRedisKeyFactory 경로와 같은 key를 만든다.
        return switch (target.getKeyType()) {
            case REMAINING_INDIVIDUAL -> trafficRedisKeyFactory.remainingIndivAmountKey(
                    target.getLineId(),
                    YearMonth.from(target.getMonthStart())
            );
            case REMAINING_SHARED -> trafficRedisKeyFactory.remainingSharedAmountKey(
                    target.getFamilyId(),
                    YearMonth.from(target.getMonthStart())
            );
            case DAILY_TOTAL_USAGE -> trafficRedisKeyFactory.dailyTotalUsageKey(
                    target.getLineId(),
                    target.getUsageDate()
            );
            case DAILY_APP_USAGE -> trafficRedisKeyFactory.dailyAppUsageKey(
                    target.getLineId(),
                    target.getUsageDate()
            );
            case DAILY_SHARED_USAGE -> trafficRedisKeyFactory.dailySharedUsageKey(
                    target.getLineId(),
                    target.getUsageDate()
            );
            case MONTHLY_SHARED_USAGE -> trafficRedisKeyFactory.monthlySharedUsageKey(
                    target.getLineId(),
                    YearMonth.from(target.getMonthStart())
            );
            case POLICY -> trafficRedisKeyFactory.policyKey(target.getPolicyId());
        };
    }

    private long readHashLong(String key, String field) {
        Object rawValue = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (rawValue == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(rawValue));
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    @FunctionalInterface
    private interface LineChunkConsumer {

        void accept(Long lineIdStartInclusive, Long lineIdEndInclusive);
    }

    private static class VerificationCounters {

        private long matchedCount;
        private long correctedCount;
        private long failedCorrectionCount;
    }
}
