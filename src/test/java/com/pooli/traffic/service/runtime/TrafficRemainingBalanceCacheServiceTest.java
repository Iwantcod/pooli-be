package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class TrafficRemainingBalanceCacheServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

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
    class HydrateBalanceTest {

        @Test
        void hydratesBalanceWithExpireAt() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);

            trafficRemainingBalanceCacheService.hydrateBalance(
                    "pooli:remaining_indiv_amount:11:202603",
                    300L,
                    1_775_833_199L
            );

            verify(hashOperations).putIfAbsent("pooli:remaining_indiv_amount:11:202603", "amount", "300");
            verify(cacheStringRedisTemplate).expireAt(
                    "pooli:remaining_indiv_amount:11:202603",
                    Instant.ofEpochSecond(1_775_833_199L)
            );
        }

        @Test
        void keepsUnlimitedSentinel() {
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);

            trafficRemainingBalanceCacheService.hydrateBalance(
                    "pooli:remaining_indiv_amount:11:202603",
                    -1L,
                    1_775_833_199L
            );

            verify(hashOperations).putIfAbsent("pooli:remaining_indiv_amount:11:202603", "amount", "-1");
        }
    }
}
