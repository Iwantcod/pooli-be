package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    "processed_individual_data",
                    "processed_shared_data",
                    "processed_qos_data",
                    "retry_count",
                    "0"
            ))
                    .thenReturn(1L);

            TrafficInFlightIdempotencyEntryResult result = trafficInFlightDedupeService.createOrGet(traceId);

            assertTrue(result.created());
            assertEquals(0L, result.entry().processedIndividualData());
            assertEquals(0L, result.entry().processedSharedData());
            assertEquals(0L, result.entry().processedQosData());
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
                    "processed_individual_data",
                    "processed_shared_data",
                    "processed_qos_data",
                    "retry_count",
                    "0"
            ))
                    .thenReturn(0L);
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get(dedupeKey, "processed_individual_data")).thenReturn("120");
            when(hashOperations.get(dedupeKey, "processed_shared_data")).thenReturn("30");
            when(hashOperations.get(dedupeKey, "processed_qos_data")).thenReturn("10");
            when(hashOperations.get(dedupeKey, "retry_count")).thenReturn("3");

            TrafficInFlightIdempotencyEntryResult result = trafficInFlightDedupeService.createOrGet(traceId);

            assertFalse(result.created());
            assertEquals(120L, result.entry().processedIndividualData());
            assertEquals(30L, result.entry().processedSharedData());
            assertEquals(10L, result.entry().processedQosData());
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
            when(hashOperations.get(dedupeKey, "processed_individual_data")).thenReturn("50");
            when(hashOperations.get(dedupeKey, "processed_shared_data")).thenReturn("20");
            when(hashOperations.get(dedupeKey, "processed_qos_data")).thenReturn("5");
            when(hashOperations.get(dedupeKey, "retry_count")).thenReturn("2");

            Optional<TrafficInFlightIdempotencyEntry> result = trafficInFlightDedupeService.get(traceId);

            assertTrue(result.isPresent());
            assertEquals(50L, result.get().processedIndividualData());
            assertEquals(20L, result.get().processedSharedData());
            assertEquals(5L, result.get().processedQosData());
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
                "processed_individual_data",
                "processed_shared_data",
                "processed_qos_data",
                "retry_count",
                "0"
        ))
                .thenReturn(4L);

        int retryCount = trafficInFlightDedupeService.incrementRetryOnReclaim(traceId);

        assertEquals(4, retryCount);
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

}
