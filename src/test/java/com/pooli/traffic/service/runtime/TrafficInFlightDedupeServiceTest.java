package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntry;
import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntryResult;
import com.pooli.traffic.domain.enums.TrafficInFlightState;

@ExtendWith(MockitoExtension.class)
class TrafficInFlightDedupeServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @InjectMocks
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @Nested
    @DisplayName("createOrGet 테스트")
    class CreateOrGetTest {

        @Test
        @DisplayName("키가 없으면 기본 필드와 함께 생성한다")
        void createsNewHashWithDefaultFields() {
            String traceId = "trace-001";
            String dedupeKey = "pooli:dedupe:run:trace-001";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(trafficLuaScriptInfraService.executeInFlightCreateIfAbsent(
                    dedupeKey,
                    "processedData",
                    "0",
                    "retryCount",
                    "0"
            ))
                    .thenReturn(1L);

            TrafficInFlightIdempotencyEntryResult result = trafficInFlightDedupeService.createOrGet(traceId);

            assertTrue(result.created());
            assertEquals(0L, result.entry().processedData());
            assertEquals(0, result.entry().retryCount());
        }

        @Test
        @DisplayName("키가 있으면 기존 필드를 정규화해 반환한다")
        void returnsExistingEntryWhenAlreadyCreated() {
            String traceId = "trace-002";
            String dedupeKey = "pooli:dedupe:run:trace-002";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(trafficLuaScriptInfraService.executeInFlightCreateIfAbsent(
                    dedupeKey,
                    "processedData",
                    "0",
                    "retryCount",
                    "0"
            ))
                    .thenReturn(0L);
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get(dedupeKey, "processedData")).thenReturn("120");
            when(hashOperations.get(dedupeKey, "retryCount")).thenReturn("3");

            TrafficInFlightIdempotencyEntryResult result = trafficInFlightDedupeService.createOrGet(traceId);

            assertFalse(result.created());
            assertEquals(120L, result.entry().processedData());
            assertEquals(3, result.entry().retryCount());
        }
    }

    @Nested
    @DisplayName("get 테스트")
    class GetTest {

        @Test
        @DisplayName("키가 없으면 empty를 반환한다")
        void returnsEmptyWhenKeyMissing() {
            String traceId = "trace-absent";
            String dedupeKey = "pooli:dedupe:run:trace-absent";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(cacheStringRedisTemplate.hasKey(dedupeKey)).thenReturn(false);

            Optional<TrafficInFlightIdempotencyEntry> result = trafficInFlightDedupeService.get(traceId);

            assertTrue(result.isEmpty());
            verify(cacheStringRedisTemplate, never()).opsForHash();
        }

        @Test
        @DisplayName("키가 있으면 hash 필드를 조회한다")
        void returnsEntryWhenKeyExists() {
            String traceId = "trace-present";
            String dedupeKey = "pooli:dedupe:run:trace-present";
            when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
            when(cacheStringRedisTemplate.hasKey(dedupeKey)).thenReturn(true);
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get(dedupeKey, "processedData")).thenReturn("50");
            when(hashOperations.get(dedupeKey, "retryCount")).thenReturn("2");

            Optional<TrafficInFlightIdempotencyEntry> result = trafficInFlightDedupeService.get(traceId);

            assertTrue(result.isPresent());
            assertEquals(50L, result.get().processedData());
            assertEquals(2, result.get().retryCount());
        }
    }

    @Test
    @DisplayName("incrementRetryOnReclaim은 retryCount를 1 증가시킨다")
    void incrementRetryOnReclaimIncrementsRetryCount() {
        String traceId = "trace-retry";
        String dedupeKey = "pooli:dedupe:run:trace-retry";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
        when(trafficLuaScriptInfraService.executeInFlightIncrementRetryWithInit(
                dedupeKey,
                "processedData",
                "0",
                "retryCount",
                "0"
        ))
                .thenReturn(4L);

        int retryCount = trafficInFlightDedupeService.incrementRetryOnReclaim(traceId);

        assertEquals(4, retryCount);
    }

    @Test
    @DisplayName("addProcessedDataAtomically는 processedData를 원자 증가시킨다")
    void addProcessedDataAtomicallyIncrementsProcessedData() {
        String traceId = "trace-processed";
        String dedupeKey = "pooli:dedupe:run:trace-processed";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
        when(trafficLuaScriptInfraService.executeInFlightIncrementProcessedWithInit(
                dedupeKey,
                "processedData",
                "0",
                "retryCount",
                "0",
                40L
        ))
                .thenReturn(140L);

        long processedData = trafficInFlightDedupeService.addProcessedDataAtomically(traceId, 40L);

        assertEquals(140L, processedData);
    }

    @Test
    @DisplayName("addProcessedDataAtomically는 음수 delta를 거부한다")
    void addProcessedDataAtomicallyRejectsNegativeDelta() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> trafficInFlightDedupeService.addProcessedDataAtomically("trace-negative", -1L)
        );
        assertEquals("delta must be 0 or greater", exception.getMessage());
    }

    @Test
    @DisplayName("delete는 멱등키를 제거한다")
    void deleteRemovesDedupeKey() {
        String traceId = "trace-delete";
        String dedupeKey = "pooli:dedupe:run:trace-delete";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);

        trafficInFlightDedupeService.delete(traceId);

        verify(cacheStringRedisTemplate).delete(dedupeKey);
    }

    @Test
    @DisplayName("호환 메서드 tryClaim/findState/release는 신규 hash 로직을 따른다")
    void legacyCompatibilityMethodsFollowHashLogic() {
        String traceId = "trace-legacy";
        String dedupeKey = "pooli:dedupe:run:trace-legacy";
        when(trafficRedisKeyFactory.dedupeRunKey(traceId)).thenReturn(dedupeKey);
        when(trafficLuaScriptInfraService.executeInFlightCreateIfAbsent(
                dedupeKey,
                "processedData",
                "0",
                "retryCount",
                "0"
        ))
                .thenReturn(1L);
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(cacheStringRedisTemplate.hasKey(dedupeKey)).thenReturn(true);
        when(hashOperations.get(dedupeKey, "processedData")).thenReturn("0");
        when(hashOperations.get(dedupeKey, "retryCount")).thenReturn("0");

        boolean claimed = trafficInFlightDedupeService.tryClaim(traceId);
        Optional<TrafficInFlightState> state = trafficInFlightDedupeService.findState(traceId);
        trafficInFlightDedupeService.release(traceId);

        assertTrue(claimed);
        assertTrue(state.isPresent());
        assertEquals(TrafficInFlightState.CLAIMED, state.get());
        verify(cacheStringRedisTemplate).delete(dedupeKey);
    }

    @Test
    @DisplayName("Redis 재시도와 DB fallback 상태는 로그로만 남긴다")
    void marksRetryAndFallbackAsLogOnly() {
        String traceId = "trace-log-only";

        assertDoesNotThrow(() -> trafficInFlightDedupeService.markRedisRetry(traceId, 1));
        assertDoesNotThrow(() -> trafficInFlightDedupeService.markRedisRetry(traceId, 2));
        assertDoesNotThrow(() -> trafficInFlightDedupeService.markRedisRetry(traceId, 3));
        assertDoesNotThrow(() -> trafficInFlightDedupeService.markDbFallback(traceId));

        verify(cacheStringRedisTemplate, never()).opsForValue();
    }
}
