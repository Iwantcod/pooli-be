package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class TrafficInFlightDedupeServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @Nested
    @DisplayName("tryClaim 테스트")
    class TryClaimTest {

        @Test
        @DisplayName("SET NX 성공 시 true 반환")
        void returnsTrueWhenClaimed() {
            // given
            String traceId = "trace-001";
            String dedupeKey = "pooli:dedupe:run:trace-001";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    eq(dedupeKey),
                    eq(traceId),
                    eq(Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC))
            )).thenReturn(true);

            // when
            boolean claimed = trafficInFlightDedupeService.tryClaim(traceId);

            // then
            assertTrue(claimed);
            verify(valueOperations).setIfAbsent(
                    dedupeKey,
                    traceId,
                    Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
            );
        }

        @Test
        @DisplayName("SET NX 실패 시 false 반환")
        void returnsFalseWhenAlreadyClaimed() {
            // given
            String traceId = "trace-001";
            String dedupeKey = "pooli:dedupe:run:trace-001";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    eq(dedupeKey),
                    eq(traceId),
                    eq(Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC))
            )).thenReturn(false);

            // when
            boolean claimed = trafficInFlightDedupeService.tryClaim(traceId);

            // then
            assertFalse(claimed);
        }
    }
}
