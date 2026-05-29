package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.domain.batch.BatchName;
import com.pooli.traffic.domain.restore.RestoreRange;
import com.pooli.traffic.domain.restore.RestoreVerificationResult;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationKeyType;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationLineRange;
import com.pooli.traffic.domain.restore.TrafficRestoreVerificationTarget;
import com.pooli.traffic.mapper.LineDailyBatchJobMapper;
import com.pooli.traffic.mapper.TrafficRestoreVerificationMapper;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreVerificationServiceTest {

    @Mock
    private TrafficRestoreVerificationMapper verificationMapper;

    @Mock
    private LineDailyBatchJobMapper batchJobMapper;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRestoreIdempotencyCleanupService idempotencyCleanupService;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private TrafficRestoreVerificationService service;

    @Test
    @DisplayName("전체 검증에서 Redis 값이 기준값과 다르면 structured log 후 기준값으로 보정한다")
    void correctsRedisValueWhenMismatchFound() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        RestoreRange restoreRange = new RestoreRange(
                LocalDate.of(2026, 5, 27),
                LocalDate.of(2026, 5, 30)
        );
        TrafficRestoreVerificationTarget target = TrafficRestoreVerificationTarget.builder()
                .keyType(TrafficRestoreVerificationKeyType.DAILY_TOTAL_USAGE)
                .lineId(10L)
                .usageDate(LocalDate.of(2026, 5, 27))
                .field("individual")
                .expectedValue(100L)
                .expireEpochSeconds(1_779_800_399L)
                .build();
        when(verificationMapper.selectVerificationLineRange(
                restoreRange.startInclusive(),
                restoreRange.endExclusive(),
                restoreRange.startDateTimeInclusive(),
                restoreRange.endDateTimeExclusive()
        )).thenReturn(TrafficRestoreVerificationLineRange.of(10L, 10L));
        when(verificationMapper.selectRemainingVerificationTargets(
                restoreRange.startInclusive(),
                restoreRange.endExclusive(),
                restoreRange.startDateTimeInclusive(),
                restoreRange.endDateTimeExclusive(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 27),
                LocalDate.of(2026, 5, 27).atStartOfDay(),
                LocalDate.of(2026, 5, 28).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of(target));
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 28),
                LocalDate.of(2026, 5, 28).atStartOfDay(),
                LocalDate.of(2026, 5, 29).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 29).atStartOfDay(),
                LocalDate.of(2026, 5, 30).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectPolicyVerificationTargets()).thenReturn(List.of());
        when(trafficRedisKeyFactory.dailyTotalUsageKey(10L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_total_usage:10:20260527");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:daily_total_usage:10:20260527", "individual")).thenReturn("10");
        when(trafficLuaScriptInfraService.executeRestoreUsageCorrection(
                "pooli:daily_total_usage:10:20260527",
                "individual",
                100L,
                1_779_800_399L
        )).thenReturn(List.of("CORRECTED"));
        when(idempotencyCleanupService.cleanupRestoreIdempotencyKeys()).thenReturn(1L);

        RestoreVerificationResult result = service.verifyAndCorrect(anchorDate, restoreRange);

        assertThat(result.failedCorrectionCount()).isZero();
        assertThat(result.correctedCount()).isEqualTo(1L);
        verify(idempotencyCleanupService).cleanupRestoreIdempotencyKeys();
    }

    @Test
    @DisplayName("보정 Lua가 CORRECTED를 반환하지 않으면 실패 count를 올리고 phase 2 batch를 FAILED로 전환한다")
    void marksPhase2FailedWhenCorrectionResultIsNotCorrected() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        RestoreRange restoreRange = new RestoreRange(
                LocalDate.of(2026, 5, 27),
                LocalDate.of(2026, 5, 30)
        );
        TrafficRestoreVerificationTarget target = TrafficRestoreVerificationTarget.builder()
                .keyType(TrafficRestoreVerificationKeyType.DAILY_TOTAL_USAGE)
                .lineId(10L)
                .usageDate(LocalDate.of(2026, 5, 27))
                .field("individual")
                .expectedValue(100L)
                .expireEpochSeconds(0L)
                .build();
        when(verificationMapper.selectVerificationLineRange(
                restoreRange.startInclusive(),
                restoreRange.endExclusive(),
                restoreRange.startDateTimeInclusive(),
                restoreRange.endDateTimeExclusive()
        )).thenReturn(TrafficRestoreVerificationLineRange.of(10L, 10L));
        when(verificationMapper.selectRemainingVerificationTargets(
                restoreRange.startInclusive(),
                restoreRange.endExclusive(),
                restoreRange.startDateTimeInclusive(),
                restoreRange.endDateTimeExclusive(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 27),
                LocalDate.of(2026, 5, 27).atStartOfDay(),
                LocalDate.of(2026, 5, 28).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of(target));
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 28),
                LocalDate.of(2026, 5, 28).atStartOfDay(),
                LocalDate.of(2026, 5, 29).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectUsageVerificationTargets(
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 29).atStartOfDay(),
                LocalDate.of(2026, 5, 30).atStartOfDay(),
                10L,
                10L
        )).thenReturn(List.of());
        when(verificationMapper.selectPolicyVerificationTargets()).thenReturn(List.of());
        when(trafficRedisKeyFactory.dailyTotalUsageKey(10L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_total_usage:10:20260527");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:daily_total_usage:10:20260527", "individual")).thenReturn("10");
        when(trafficLuaScriptInfraService.executeRestoreUsageCorrection(
                "pooli:daily_total_usage:10:20260527",
                "individual",
                100L,
                0L
        )).thenReturn(List.of("ERROR", "WRITE_FAILED"));

        RestoreVerificationResult result = service.verifyAndCorrect(anchorDate, restoreRange, 55L);

        assertThat(result.failedCorrectionCount()).isEqualTo(1L);
        assertThat(result.correctedCount()).isZero();
        assertThat(result.idempotencyCleanedCount()).isZero();
        verify(idempotencyCleanupService, never()).cleanupRestoreIdempotencyKeys();
        verify(batchJobMapper).failRunningRestorePhaseBatch(
                55L,
                BatchName.RESTORE_P2_DONE_LOG_REPLAY,
                "RESTORE_CORRECTION_FAILED",
                "Redis 복구 검증 보정 실패 count=1"
        );
    }
}
