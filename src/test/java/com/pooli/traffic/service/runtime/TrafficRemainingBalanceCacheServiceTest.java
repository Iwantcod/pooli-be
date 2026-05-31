package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
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
    class ReadHashFieldOrDefaultTest {

        @Test
        void returnsParsedHashField() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:monthly_shared_usage:11:202603", "usage_amount")).thenReturn("300");

            long amount = trafficRemainingBalanceCacheService.readHashFieldOrDefault(
                    "pooli:monthly_shared_usage:11:202603",
                    "usage_amount",
                    10L
            );

            assertEquals(300L, amount);
        }

        @Test
        void returnsDefaultWhenHashFieldMalformed() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:monthly_shared_usage:11:202603", "usage_amount"))
                    .thenReturn("not-a-number");

            long amount = trafficRemainingBalanceCacheService.readHashFieldOrDefault(
                    "pooli:monthly_shared_usage:11:202603",
                    "usage_amount",
                    77L
            );

            assertEquals(77L, amount);
        }
    }

    @Nested
    class HasHashFieldTest {

        @Test
        void returnsTrueWhenHashFieldExists() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.hasKey("pooli:remaining_indiv_amount:11:202603", "qos")).thenReturn(true);

            boolean result = trafficRemainingBalanceCacheService.hasHashField(
                    "pooli:remaining_indiv_amount:11:202603",
                    "qos"
            );

            assertTrue(result);
        }

        @Test
        void returnsFalseWhenHashFieldMissing() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.hasKey("pooli:remaining_indiv_amount:11:202603", "qos")).thenReturn(false);

            boolean result = trafficRemainingBalanceCacheService.hasHashField(
                    "pooli:remaining_indiv_amount:11:202603",
                    "qos"
            );

            assertFalse(result);
        }
    }

    @Nested
    class HasKeyTest {

        @Test
        void returnsTrueWhenRedisKeyExists() {
            when(cacheStringRedisTemplate.hasKey("pooli:remaining_shared_amount:22:202603")).thenReturn(true);

            boolean result = trafficRemainingBalanceCacheService.hasKey("pooli:remaining_shared_amount:22:202603");

            assertTrue(result);
        }

        @Test
        void returnsFalseWhenRedisKeyMissing() {
            when(cacheStringRedisTemplate.hasKey("pooli:remaining_shared_amount:22:202603")).thenReturn(false);

            boolean result = trafficRemainingBalanceCacheService.hasKey("pooli:remaining_shared_amount:22:202603");

            assertFalse(result);
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

    @Nested
    class IncrementAmountIfPresentTest {

        @Test
        @DisplayName("amount가 있으면 delta를 적용한다")
        void incrementsAmountWhenPresent() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_shared_amount:22:202603", "amount")).thenReturn("500");

            boolean result = trafficRemainingBalanceCacheService.incrementAmountIfPresent(
                    "pooli:remaining_shared_amount:22:202603",
                    100L
            );

            assertTrue(result);
            verify(hashOperations).increment("pooli:remaining_shared_amount:22:202603", "amount", 100L);
        }

        @Test
        @DisplayName("amount가 없으면 Redis 값을 만들지 않는다")
        void skipsWhenAmountMissing() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_shared_amount:22:202603", "amount")).thenReturn(null);

            boolean result = trafficRemainingBalanceCacheService.incrementAmountIfPresent(
                    "pooli:remaining_shared_amount:22:202603",
                    100L
            );

            assertFalse(result);
            verify(hashOperations, never()).increment("pooli:remaining_shared_amount:22:202603", "amount", 100L);
        }

        @Test
        @DisplayName("무제한 sentinel은 개인 기여 차감으로 변경하지 않는다")
        void keepsUnlimitedSentinelWhenDecrementRequested() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_indiv_amount:11:202603", "amount")).thenReturn("-1");

            boolean result = trafficRemainingBalanceCacheService.incrementAmountIfPresent(
                    "pooli:remaining_indiv_amount:11:202603",
                    -100L
            );

            assertTrue(result);
            verify(hashOperations, never()).increment("pooli:remaining_indiv_amount:11:202603", "amount", -100L);
        }
    }

    @Nested
    @DisplayName("캐시 준비성 검증 테스트")
    class ReadinessCheckTest {

        @Test
        @DisplayName("개인 스냅샷 해시에 amount와 qos가 정상 존재하면 true 반환")
        void isIndividualReadyReturnsTrueWhenFieldsExistAndNonEmpty() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.multiGet("pooli:remaining_indiv_amount:11:202605", java.util.List.of("amount", "qos")))
                    .thenReturn(java.util.List.of("300", "250"));

            boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

            assertTrue(result);
        }

        @Test
        @DisplayName("개인 스냅샷 해시에 amount가 누락되면 false 반환")
        void isIndividualReadyReturnsFalseWhenAmountMissing() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.multiGet("pooli:remaining_indiv_amount:11:202605", java.util.List.of("amount", "qos")))
                    .thenReturn(java.util.Arrays.asList(null, "250"));

            boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

            assertFalse(result);
        }

        @Test
        @DisplayName("개인 스냅샷 해시에 qos가 누락되면 false 반환")
        void isIndividualReadyReturnsFalseWhenQosMissing() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.multiGet("pooli:remaining_indiv_amount:11:202605", java.util.List.of("amount", "qos")))
                    .thenReturn(java.util.Arrays.asList("300", null));

            boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

            assertFalse(result);
        }

        @Test
        @DisplayName("개인 스냅샷 해시의 amount가 공백 문자열이면 false 반환")
        void isIndividualReadyReturnsFalseWhenAmountIsBlank() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.multiGet("pooli:remaining_indiv_amount:11:202605", java.util.List.of("amount", "qos")))
                    .thenReturn(java.util.List.of(" ", "250"));

            boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

            assertFalse(result);
        }

        @Test
        @DisplayName("개인 스냅샷 해시의 qos가 공백 문자열이면 false 반환")
        void isIndividualReadyReturnsFalseWhenQosIsBlank() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.multiGet("pooli:remaining_indiv_amount:11:202605", java.util.List.of("amount", "qos")))
                    .thenReturn(java.util.List.of("300", " "));

            boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

            assertFalse(result);
        }

        @Test
        @DisplayName("공유 스냅샷 해시에 amount가 정상 존재하면 true 반환")
        void isSharedReadyReturnsTrueWhenAmountExistsAndNonEmpty() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn("500");

            boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

            assertTrue(result);
        }

        @Test
        @DisplayName("공유 스냅샷 해시에 amount가 누락되면 false 반환")
        void isSharedReadyReturnsFalseWhenAmountMissing() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn(null);

            boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

            assertFalse(result);
        }

        @Test
        @DisplayName("공유 스냅샷 해시의 amount가 공백 문자열이면 false 반환")
        void isSharedReadyReturnsFalseWhenAmountIsBlank() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn(" ");

            boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

            assertFalse(result);
        }
    }
}
