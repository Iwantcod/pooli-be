package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class TrafficRemainingBalanceCacheServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ObjectProvider<TrafficLuaScriptInfraService> trafficLuaScriptInfraServiceProvider;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @InjectMocks
    private TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;

    @Nested
    class ReadAmountOrDefaultTest {

        @Test
        void returnsParsedAmount() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_indiv_amount:11:202603", "amount")).thenReturn("300");

            long amount = trafficRemainingBalanceCacheService.readAmountOrDefault(
                    "pooli:remaining_indiv_amount:11:202603",
                    10L
            );

            assertEquals(300L, amount);
        }

        @Test
        void returnsDefaultWhenAmountMalformed() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_indiv_amount:11:202603", "amount")).thenReturn("not-a-number");

            long amount = trafficRemainingBalanceCacheService.readAmountOrDefault(
                    "pooli:remaining_indiv_amount:11:202603",
                    77L
            );

            assertEquals(77L, amount);
        }
    }

    @Nested
    class HydrateSnapshotTest {

        @Test
        void hydratesIndividualSnapshotAtomically() {
            when(trafficLuaScriptInfraServiceProvider.getIfAvailable()).thenReturn(trafficLuaScriptInfraService);
            when(trafficLuaScriptInfraService.executeHydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    300L,
                    125L,
                    1_775_833_199L
            )).thenReturn(1L);

            trafficRemainingBalanceCacheService.hydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    300L,
                    125L,
                    1_775_833_199L
            );

            verify(trafficLuaScriptInfraService).executeHydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    300L,
                    125L,
                    1_775_833_199L
            );
        }

        @Test
        void hydratesSharedSnapshotAtomically() {
            when(trafficLuaScriptInfraServiceProvider.getIfAvailable()).thenReturn(trafficLuaScriptInfraService);
            when(trafficLuaScriptInfraService.executeHydrateSharedSnapshot(
                    "pooli:remaining_shared_amount:22:202603",
                    500L,
                    1_775_833_199L
            )).thenReturn(1L);

            trafficRemainingBalanceCacheService.hydrateSharedSnapshot(
                    "pooli:remaining_shared_amount:22:202603",
                    500L,
                    1_775_833_199L
            );

            verify(trafficLuaScriptInfraService).executeHydrateSharedSnapshot(
                    "pooli:remaining_shared_amount:22:202603",
                    500L,
                    1_775_833_199L
            );
        }

        @Test
        void keepsUnlimitedSentinel() {
            when(trafficLuaScriptInfraServiceProvider.getIfAvailable()).thenReturn(trafficLuaScriptInfraService);
            when(trafficLuaScriptInfraService.executeHydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    -1L,
                    0L,
                    1_775_833_199L
            )).thenReturn(1L);

            trafficRemainingBalanceCacheService.hydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    -1L,
                    -20L,
                    1_775_833_199L
            );

            verify(trafficLuaScriptInfraService).executeHydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    -1L,
                    0L,
                    1_775_833_199L
            );
        }

        @Test
        void throwsWhenLuaRejectsSnapshot() {
            when(trafficLuaScriptInfraServiceProvider.getIfAvailable()).thenReturn(trafficLuaScriptInfraService);
            when(trafficLuaScriptInfraService.executeHydrateIndividualSnapshot(
                    "pooli:remaining_indiv_amount:11:202603",
                    -2L,
                    0L,
                    1_775_833_199L
            )).thenReturn(-1L);

            assertThrows(
                    IllegalStateException.class,
                    () -> trafficRemainingBalanceCacheService.hydrateIndividualSnapshot(
                            "pooli:remaining_indiv_amount:11:202603",
                            -2L,
                            0L,
                            1_775_833_199L
                    )
            );
        }

        @Test
        void throwsWhenLuaInfraServiceUnavailable() {
            when(trafficLuaScriptInfraServiceProvider.getIfAvailable()).thenReturn(null);

            assertThrows(
                    IllegalStateException.class,
                    () -> trafficRemainingBalanceCacheService.hydrateSharedSnapshot(
                            "pooli:remaining_shared_amount:22:202603",
                            500L,
                            1_775_833_199L
                    )
            );
        }
    }
}
