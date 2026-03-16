package com.pooli.traffic.service.decision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.pooli.monitoring.metrics.TrafficDeductFallbackMetrics;
import com.pooli.traffic.domain.entity.TrafficRedisUsageDeltaRecord;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class TrafficUsageDeltaReplaySchedulerTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficUsageDeltaRecordService trafficUsageDeltaRecordService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficDeductFallbackMetrics trafficDeductFallbackMetrics;

    @InjectMocks
    private TrafficUsageDeltaReplayScheduler trafficUsageDeltaReplayScheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trafficUsageDeltaReplayScheduler, "batchSize", 10);
        ReflectionTestUtils.setField(trafficUsageDeltaReplayScheduler, "maxRetryCount", 20);
        ReflectionTestUtils.setField(trafficUsageDeltaReplayScheduler, "processingStuckSeconds", 180);
    }

    @Test
    @DisplayName("replay 성공 시 SUCCESS 상태로 전이한다")
    void marksSuccessWhenReplaySucceeds() {
        // given
        TrafficRedisUsageDeltaRecord record = record(1L, 0);
        when(trafficUsageDeltaRecordService.lockReplayCandidatesAndMarkProcessing(10, 180))
                .thenReturn(List.of(record));
        when(trafficUsageDeltaRecordService.countBacklog()).thenReturn(0L);

        when(trafficRedisKeyFactory.usageDeltaReplayIdempotencyKey("trace-001", TrafficPoolType.INDIVIDUAL.name()))
                .thenReturn("pooli:usage:idempotency:trace-001:INDIVIDUAL");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(11L, LocalDate.of(2026, 3, 16)))
                .thenReturn("pooli:daily_total_usage:11:20260316");
        when(trafficRedisKeyFactory.dailyAppUsageKey(11L, LocalDate.of(2026, 3, 16)))
                .thenReturn("pooli:daily_app_usage:11:20260316");
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(LocalDate.of(2026, 3, 16)))
                .thenReturn(1_774_345_600L);
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(YearMonth.of(2026, 3)))
                .thenReturn(1_776_000_000L);

        when(cacheStringRedisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        // when
        trafficUsageDeltaReplayScheduler.runReplayCycle();

        // then
        verify(trafficUsageDeltaRecordService).markSuccess(1L);
        verify(trafficUsageDeltaRecordService, never()).markFailWithRetryIncrement(eq(1L), any());
        verify(trafficDeductFallbackMetrics).incrementReplayResult("success");
    }

    @Test
    @DisplayName("replay 실패 시 FAIL + retry_count 증가로 전이한다")
    void marksFailWithRetryIncrementWhenReplayFails() {
        // given
        TrafficRedisUsageDeltaRecord record = record(2L, 0);
        when(trafficUsageDeltaRecordService.lockReplayCandidatesAndMarkProcessing(10, 180))
                .thenReturn(List.of(record));
        when(trafficUsageDeltaRecordService.countBacklog()).thenReturn(1L);

        when(trafficRedisKeyFactory.usageDeltaReplayIdempotencyKey("trace-001", TrafficPoolType.INDIVIDUAL.name()))
                .thenReturn("pooli:usage:idempotency:trace-001:INDIVIDUAL");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(11L, LocalDate.of(2026, 3, 16)))
                .thenReturn("pooli:daily_total_usage:11:20260316");
        when(trafficRedisKeyFactory.dailyAppUsageKey(11L, LocalDate.of(2026, 3, 16)))
                .thenReturn("pooli:daily_app_usage:11:20260316");
        when(trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(LocalDate.of(2026, 3, 16)))
                .thenReturn(1_774_345_600L);
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(YearMonth.of(2026, 3)))
                .thenReturn(1_776_000_000L);

        when(cacheStringRedisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        // when
        trafficUsageDeltaReplayScheduler.runReplayCycle();

        // then
        verify(trafficUsageDeltaRecordService).markFailWithRetryIncrement(eq(2L), any());
        verify(trafficDeductFallbackMetrics).incrementReplayResult("fail");
    }

    private TrafficRedisUsageDeltaRecord record(Long id, int retryCount) {
        return TrafficRedisUsageDeltaRecord.builder()
                .id(id)
                .traceId("trace-001")
                .poolType(TrafficPoolType.INDIVIDUAL)
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .usedBytes(100L)
                .usageDate(LocalDate.of(2026, 3, 16))
                .targetMonth("2026-03")
                .retryCount(retryCount)
                .build();
    }
}
