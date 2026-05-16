package com.pooli.traffic.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult;
import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult.Status;
import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;

@ExtendWith(MockitoExtension.class)
class TrafficBalanceSnapshotHydrateServiceTest {

    @Mock
    private TrafficRefillSourceMapper trafficRefillSourceMapper;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @InjectMocks
    private TrafficBalanceSnapshotHydrateService service;

    @Test
    @DisplayName("개인 snapshot이 현재월이면 Redis에 amount와 qos를 적재")
    void hydrateIndividualSnapshot_hydratesReadySnapshot() {
        YearMonth targetMonth = YearMonth.of(2026, 5);
        TrafficIndividualBalanceSnapshot snapshot = TrafficIndividualBalanceSnapshot.builder()
                .lineId(11L)
                .amount(300L)
                .qosSpeedLimit(2L)
                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build();
        stubIndividualHydrateLockAcquired(11L);
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L)).thenReturn(snapshot);
        when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)).thenReturn(1_779_033_599L);

        TrafficBalanceSnapshotHydrateResult result = service.hydrateIndividualSnapshot(11L, targetMonth);

        assertThat(result.status()).isEqualTo(Status.HYDRATED);
        verify(trafficRemainingBalanceCacheService)
                .hydrateIndividualSnapshot("indiv:11", 300L, 250L, 1_779_033_599L);
    }

    @Test
    @DisplayName("공유 snapshot이 과거월이면 RDB source 갱신 후 Redis에 적재")
    void hydrateSharedSnapshot_refreshesStaleSnapshotBeforeHydrate() {
        YearMonth targetMonth = YearMonth.of(2026, 5);
        TrafficSharedBalanceSnapshot staleSnapshot = TrafficSharedBalanceSnapshot.builder()
                .familyId(22L)
                .amount(100L)
                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
        TrafficSharedBalanceSnapshot refreshedSnapshot = TrafficSharedBalanceSnapshot.builder()
                .familyId(22L)
                .amount(500L)
                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build();
        stubSharedHydrateLockAcquired(22L);
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(staleSnapshot, refreshedSnapshot);
        when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");
        when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)).thenReturn(1_779_033_599L);

        TrafficBalanceSnapshotHydrateResult result = service.hydrateSharedSnapshot(22L, targetMonth);

        assertThat(result.status()).isEqualTo(Status.HYDRATED);
        verify(trafficRefillSourceMapper).refreshSharedBalanceIfBeforeTargetMonth(
                22L,
                targetMonth.atDay(1).atStartOfDay()
        );
        verify(trafficRemainingBalanceCacheService)
                .hydrateSharedSnapshot("shared:22", 500L, 1_779_033_599L);
    }

    @Test
    @DisplayName("targetMonth가 RDB refresh 월보다 과거면 STALE_TARGET_MONTH를 반환하고 Redis에 적재하지 않는다")
    void hydrateIndividualSnapshot_returnsStaleTargetMonthWhenTargetBeforeRefreshMonth() {
        YearMonth targetMonth = YearMonth.of(2026, 5);
        stubIndividualHydrateLockAcquired(11L);
        when(trafficRefillSourceMapper.selectIndividualBalanceSnapshot(11L))
                .thenReturn(TrafficIndividualBalanceSnapshot.builder()
                        .lineId(11L)
                        .amount(300L)
                        .qosSpeedLimit(2L)
                        .lastBalanceRefreshedAt(LocalDateTime.of(2026, 6, 1, 0, 0))
                        .build());

        TrafficBalanceSnapshotHydrateResult result = service.hydrateIndividualSnapshot(11L, targetMonth);

        assertThat(result.status()).isEqualTo(Status.STALE_TARGET_MONTH);
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateIndividualSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
    }

    @Test
    @DisplayName("과거월 refresh 후에도 targetMonth snapshot이 아니면 NOT_READY를 반환한다")
    void hydrateSharedSnapshot_returnsNotReadyWhenRefreshDoesNotConverge() {
        YearMonth targetMonth = YearMonth.of(2026, 5);
        TrafficSharedBalanceSnapshot staleSnapshot = TrafficSharedBalanceSnapshot.builder()
                .familyId(22L)
                .amount(100L)
                .lastBalanceRefreshedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
        stubSharedHydrateLockAcquired(22L);
        when(trafficRefillSourceMapper.selectSharedBalanceSnapshot(22L))
                .thenReturn(staleSnapshot, staleSnapshot);

        TrafficBalanceSnapshotHydrateResult result = service.hydrateSharedSnapshot(22L, targetMonth);

        assertThat(result.status()).isEqualTo(Status.NOT_READY);
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateSharedSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
    }

    @Test
    @DisplayName("hydrate lock을 획득하지 못하면 RDB 조회 없이 NOT_READY를 반환한다")
    void hydrateIndividualSnapshot_returnsNotReadyWhenLockIsNotAcquired() {
        YearMonth targetMonth = YearMonth.of(2026, 5);
        when(trafficRedisKeyFactory.indivHydrateLockKey(11L)).thenReturn("indiv-lock:11");
        when(trafficLuaScriptInfraService.tryAcquireHydrateLock("indiv-lock:11")).thenReturn(Optional.empty());

        TrafficBalanceSnapshotHydrateResult result = service.hydrateIndividualSnapshot(11L, targetMonth);

        assertThat(result.status()).isEqualTo(Status.NOT_READY);
        verify(trafficRefillSourceMapper, never()).selectIndividualBalanceSnapshot(11L);
        verify(trafficRemainingBalanceCacheService, never())
                .hydrateIndividualSnapshot(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                );
        verify(trafficLuaScriptInfraService, never()).releaseHydrateLock(any());
    }

    private void stubIndividualHydrateLockAcquired(Long lineId) {
        when(trafficRedisKeyFactory.indivHydrateLockKey(lineId)).thenReturn("indiv-lock:" + lineId);
        stubHydrateLockAcquired("indiv-lock:" + lineId);
    }

    private void stubSharedHydrateLockAcquired(Long familyId) {
        when(trafficRedisKeyFactory.sharedHydrateLockKey(familyId)).thenReturn("shared-lock:" + familyId);
        stubHydrateLockAcquired("shared-lock:" + familyId);
    }

    private void stubHydrateLockAcquired(String lockKey) {
        when(trafficLuaScriptInfraService.tryAcquireHydrateLock(lockKey))
                .thenReturn(Optional.of(new TrafficLuaScriptInfraService.HydrateLockHandle(lockKey, "owner")));
    }
}
