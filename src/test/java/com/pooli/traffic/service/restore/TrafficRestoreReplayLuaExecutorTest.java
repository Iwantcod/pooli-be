package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreReplayLuaExecutorTest {

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @InjectMocks
    private TrafficRestoreReplayLuaExecutor executor;

    @Test
    @DisplayName("replay executor는 idempotency suffix를 key factory로 namespace 처리한다")
    void normalizesIdempotencySuffixThroughKeyFactory() {
        String suffix = "p1:daily_app:20260527:100:20";
        when(trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(suffix))
                .thenReturn("pooli:restore:idempotency:p1:daily_app:20260527:100:20");
        when(trafficRedisKeyFactory.remainingIndivAmountKey(100L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:remaining_indiv_amount:100:202605");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(200L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:remaining_shared_amount:200:202605");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_total_usage:100:20260527");
        when(trafficRedisKeyFactory.dailyAppUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_app_usage:100:20260527");
        when(trafficRedisKeyFactory.dailySharedUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_shared_usage:100:20260527");
        when(trafficRedisKeyFactory.monthlySharedUsageKey(100L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:monthly_shared_usage:100:202605");
        when(trafficLuaScriptInfraService.executeRestoreUsageReplay(anyList(), anyList()))
                .thenReturn(List.of("APPLIED"));
        TrafficRestoreReplayCommand command = TrafficRestoreReplayCommand.builder()
                .idempotencyKey(suffix)
                .usageDate(LocalDate.of(2026, 5, 27))
                .lineId(100L)
                .familyId(200L)
                .applicationId(20)
                .individualUsageBytes(1L)
                .sharedUsageBytes(2L)
                .qosUsageBytes(3L)
                .expireEpochSeconds(0L)
                .build();

        executor.replay(command);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService).executeRestoreUsageReplay(keysCaptor.capture(), anyList());
        assertThat(keysCaptor.getValue().get(0))
                .isEqualTo("pooli:restore:idempotency:p1:daily_app:20260527:100:20");
    }

    @Test
    @DisplayName("replay executor는 Lua에 familyId를 여섯 번째 인자로 전달한다")
    void passesFamilyIdToReplayLua() {
        String suffix = "p2:done_log:9001";
        when(trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(suffix))
                .thenReturn("pooli:restore:idempotency:p2:done_log:9001");
        when(trafficRedisKeyFactory.remainingIndivAmountKey(100L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:remaining_indiv_amount:100:202605");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(200L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:remaining_shared_amount:200:202605");
        when(trafficRedisKeyFactory.dailyTotalUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_total_usage:100:20260527");
        when(trafficRedisKeyFactory.dailyAppUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_app_usage:100:20260527");
        when(trafficRedisKeyFactory.dailySharedUsageKey(100L, LocalDate.of(2026, 5, 27)))
                .thenReturn("pooli:daily_shared_usage:100:20260527");
        when(trafficRedisKeyFactory.monthlySharedUsageKey(100L, YearMonth.of(2026, 5)))
                .thenReturn("pooli:monthly_shared_usage:100:202605");
        when(trafficLuaScriptInfraService.executeRestoreUsageReplay(anyList(), anyList()))
                .thenReturn(List.of("APPLIED"));
        TrafficRestoreReplayCommand command = TrafficRestoreReplayCommand.builder()
                .idempotencyKey(suffix)
                .usageDate(LocalDate.of(2026, 5, 27))
                .lineId(100L)
                .familyId(200L)
                .applicationId(20)
                .individualUsageBytes(1L)
                .sharedUsageBytes(2L)
                .qosUsageBytes(3L)
                .expireEpochSeconds(0L)
                .build();

        executor.replay(command);

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLuaScriptInfraService).executeRestoreUsageReplay(anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("20", "1", "2", "3", "0", "200");
    }

    @Test
    @DisplayName("idempotency key 삭제도 suffix를 key factory로 namespace 처리한다")
    void normalizesIdempotencySuffixBeforeDelete() {
        String suffix = "p1:daily_app:20260527:100:20";
        when(trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(suffix))
                .thenReturn("pooli:restore:idempotency:p1:daily_app:20260527:100:20");

        executor.deleteIdempotencyKey(suffix);

        verify(cacheStringRedisTemplate).delete("pooli:restore:idempotency:p1:daily_app:20260527:100:20");
    }
}
