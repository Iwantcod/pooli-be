package com.pooli.traffic.service.runtime;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficBalanceStateWriteThroughServiceTest {

    @Mock
    private TrafficFamilyMetaCacheService trafficFamilyMetaCacheService;

    @Mock
    private TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    private TrafficBalanceStateWriteThroughService service;

    @BeforeEach
    void setUp() {
        service = new TrafficBalanceStateWriteThroughService(
                trafficFamilyMetaCacheService,
                trafficRemainingBalanceCacheService,
                trafficRedisKeyFactory,
                trafficRedisRuntimePolicy
        );
    }

    @Test
    @DisplayName("공유풀 기여 write-through는 현재월 개인 amount를 줄이고 공유 amount를 늘린다")
    void markSharedPoolContribution_updatesExistingBalanceAmounts() {
        ZoneId zoneId = ZoneId.of("Asia/Seoul");
        YearMonth targetMonth = YearMonth.now(zoneId);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(zoneId);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");

        service.markSharedPoolContribution(11L, 22L, 100L, false);

        verify(trafficRemainingBalanceCacheService).incrementAmountIfPresent("indiv:11", -100L);
        verify(trafficRemainingBalanceCacheService).incrementAmountIfPresent("shared:22", 100L);
        verify(trafficFamilyMetaCacheService).increasePoolTotal(22L, 100L);
    }

    @Test
    @DisplayName("무제한 개인풀 기여는 개인 amount 차감을 요청하지 않는다")
    void markSharedPoolContribution_skipsIndividualAmountForUnlimitedLine() {
        ZoneId zoneId = ZoneId.of("Asia/Seoul");
        YearMonth targetMonth = YearMonth.now(zoneId);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(zoneId);
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");

        service.markSharedPoolContribution(11L, 22L, 100L, true);

        verify(trafficRedisKeyFactory, never()).remainingIndivAmountKey(11L, targetMonth);
        verify(trafficRemainingBalanceCacheService, never()).incrementAmountIfPresent("indiv:11", -100L);
        verify(trafficRemainingBalanceCacheService).incrementAmountIfPresent("shared:22", 100L);
        verify(trafficFamilyMetaCacheService).increasePoolTotal(22L, 100L);
    }
}
