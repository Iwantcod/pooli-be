package com.pooli.traffic.service.runtime;

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

import com.pooli.traffic.domain.enums.TrafficInFlightState;

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
                    eq(TrafficInFlightState.CLAIMED.name()),
                    eq(Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC))
            )).thenReturn(true);

            // when
            boolean claimed = trafficInFlightDedupeService.tryClaim(traceId);

            // then
            assertTrue(claimed);
            verify(valueOperations).setIfAbsent(
                    dedupeKey,
                    TrafficInFlightState.CLAIMED.name(),
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
                    eq(TrafficInFlightState.CLAIMED.name()),
                    eq(Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC))
            )).thenReturn(false);

            // when
            boolean claimed = trafficInFlightDedupeService.tryClaim(traceId);

            // then
            assertFalse(claimed);
        }
    }

    @Test
    @DisplayName("release 호출 시 DONE 상태를 TTL과 함께 기록한다")
    void releaseWritesDoneState() {
        // given
        String traceId = "trace-001";
        String dedupeKey = "pooli:dedupe:run:trace-001";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        trafficInFlightDedupeService.release(traceId);

        // then
        verify(valueOperations).set(
                dedupeKey,
                TrafficInFlightState.DONE.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
    }

    @Test
    @DisplayName("Redis 재시도와 DB fallback 상태를 기록한다")
    void writesRetryAndFallbackStates() {
        // given
        String traceId = "trace-001";
        String dedupeKey = "pooli:dedupe:run:trace-001";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        trafficInFlightDedupeService.markRedisRetry(traceId, 1);
        trafficInFlightDedupeService.markRedisRetry(traceId, 2);
        trafficInFlightDedupeService.markRedisRetry(traceId, 3);
        trafficInFlightDedupeService.markDbFallback(traceId);

        // then
        verify(valueOperations).set(
                dedupeKey,
                TrafficInFlightState.REDIS_RETRY_1.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
        verify(valueOperations).set(
                dedupeKey,
                TrafficInFlightState.REDIS_RETRY_2.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
        verify(valueOperations).set(
                dedupeKey,
                TrafficInFlightState.REDIS_RETRY_3.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
        verify(valueOperations).set(
                dedupeKey,
                TrafficInFlightState.DB_FALLBACK.name(),
                Duration.ofSeconds(TrafficRedisRuntimePolicy.INFLIGHT_TTL_SEC)
        );
    }
}
