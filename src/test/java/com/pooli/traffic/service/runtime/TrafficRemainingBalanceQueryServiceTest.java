package com.pooli.traffic.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TrafficRemainingBalanceQueryServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock
    private ObjectProvider<TrafficRedisKeyFactory> trafficRedisKeyFactoryProvider;

    @Mock
    private ObjectProvider<TrafficRedisRuntimePolicy> trafficRedisRuntimePolicyProvider;

    @Mock
    private ObjectProvider<TrafficRemainingBalanceCacheService> trafficRemainingBalanceCacheServiceProvider;

    @Mock
    private ObjectProvider<TrafficBalanceSnapshotHydrateService> trafficBalanceSnapshotHydrateServiceProvider;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;

    @Mock
    private TrafficBalanceSnapshotHydrateService trafficBalanceSnapshotHydrateService;

    private TrafficRemainingBalanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new TrafficRemainingBalanceQueryService(
                trafficRedisKeyFactoryProvider,
                trafficRedisRuntimePolicyProvider,
                trafficRemainingBalanceCacheServiceProvider,
                trafficBalanceSnapshotHydrateServiceProvider
        );
    }

    @Test
    @DisplayName("개인 잔량: Redis amount가 있으면 해당 값만 반환")
    void resolveIndividualActualRemaining_returnsRedisAmountOnly() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
        when(trafficRemainingBalanceCacheService.readAmount("indiv:11")).thenReturn(Optional.of(300L));

        Long result = service.resolveIndividualActualRemaining(11L);

        assertThat(result).isEqualTo(300L);
        verify(trafficBalanceSnapshotHydrateServiceProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("공유 잔량: Redis amount 0은 missing이 아니라 실제 잔량 0")
    void resolveSharedActualRemaining_preservesZeroAmount() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");
        when(trafficRemainingBalanceCacheService.readAmount("shared:22")).thenReturn(Optional.of(0L));

        Long result = service.resolveSharedActualRemaining(22L);

        assertThat(result).isZero();
        verify(trafficBalanceSnapshotHydrateServiceProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("개인 잔량: Redis amount -1은 무제한 sentinel로 반환")
    void resolveIndividualActualRemaining_preservesUnlimitedSentinel() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
        when(trafficRemainingBalanceCacheService.readAmount("indiv:11")).thenReturn(Optional.of(-1L));

        Long result = service.resolveIndividualActualRemaining(11L);

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("공유 잔량: Redis amount가 없으면 hydrate 후 Redis amount를 다시 읽는다")
    void resolveSharedActualRemaining_hydratesWhenRedisMissing() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");
        when(trafficRemainingBalanceCacheService.readAmount("shared:22"))
                .thenReturn(Optional.empty(), Optional.of(500L));
        when(trafficBalanceSnapshotHydrateServiceProvider.getIfAvailable()).thenReturn(trafficBalanceSnapshotHydrateService);
        when(trafficBalanceSnapshotHydrateService.hydrateSharedSnapshot(22L, targetMonth))
                .thenReturn(TrafficBalanceSnapshotHydrateResult.hydrated());

        Long result = service.resolveSharedActualRemaining(22L);

        assertThat(result).isEqualTo(500L);
        verify(trafficBalanceSnapshotHydrateService).hydrateSharedSnapshot(22L, targetMonth);
    }

    @Test
    @DisplayName("개인 잔량: hydrate 실패 시 DB fallback 없이 null 반환")
    void resolveIndividualActualRemaining_returnsNullWhenHydrateFails() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
        when(trafficRemainingBalanceCacheService.readAmount("indiv:11")).thenReturn(Optional.empty());
        when(trafficBalanceSnapshotHydrateServiceProvider.getIfAvailable()).thenReturn(trafficBalanceSnapshotHydrateService);
        when(trafficBalanceSnapshotHydrateService.hydrateIndividualSnapshot(11L, targetMonth))
                .thenReturn(TrafficBalanceSnapshotHydrateResult.notReady());

        Long result = service.resolveIndividualActualRemaining(11L);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("공유 잔량: Redis 조회 실패 시 DB fallback 없이 null 반환")
    void resolveSharedActualRemaining_returnsNullWhenRedisReadFails() {
        YearMonth targetMonth = stubRuntimeServices();
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");
        when(trafficRemainingBalanceCacheService.readAmount("shared:22"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        Long result = service.resolveSharedActualRemaining(22L);

        assertThat(result).isNull();
    }

    private YearMonth stubRuntimeServices() {
        YearMonth targetMonth = YearMonth.now(ZONE_ID);
        when(trafficRedisKeyFactoryProvider.getIfAvailable()).thenReturn(trafficRedisKeyFactory);
        when(trafficRedisRuntimePolicyProvider.getIfAvailable()).thenReturn(trafficRedisRuntimePolicy);
        when(trafficRemainingBalanceCacheServiceProvider.getIfAvailable()).thenReturn(trafficRemainingBalanceCacheService);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZONE_ID);
        return targetMonth;
    }
}
