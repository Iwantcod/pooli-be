package com.pooli.traffic.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;

@ExtendWith(MockitoExtension.class)
class TrafficLuaScriptInfraServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    private TrafficLuaScriptInfraService service;

    @BeforeEach
    void setUp() {
        service = new TrafficLuaScriptInfraService(
                cacheStringRedisTemplate,
                new ObjectMapper(),
                trafficRedisAvailabilityMetrics,
                trafficRedisFailureClassifier
        );
    }

    @Test
    @DisplayName("hydrate lock 획득 시 10초 TTL과 owner 값을 반환한다")
    void tryAcquireHydrateLock_returnsOwnerWhenAcquired() {
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:11"), anyString(), eq(Duration.ofMillis(10_000L))))
                .thenReturn(true);

        Optional<TrafficLuaScriptInfraService.HydrateLockHandle> result =
                service.tryAcquireHydrateLock("lock:11");

        assertThat(result).isPresent();
        assertThat(result.get().lockKey()).isEqualTo("lock:11");
        assertThat(result.get().lockOwner()).startsWith("hydrate-lock-owner:");
    }

    @Test
    @DisplayName("두 번째 hydrate lock 획득 실패 시 먼저 잡은 lock을 즉시 해제한다")
    void tryAcquireHydrateLocks_releasesFirstLockWhenSecondFails() {
        TrafficLuaScriptInfraService spyService = spy(service);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofMillis(10_000L))))
                .thenReturn(true, false);
        doReturn(true).when(spyService).releaseHydrateLock(any());

        Optional<TrafficLuaScriptInfraService.HydrateLockPair> result =
                spyService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22");

        assertThat(result).isEmpty();
        verify(spyService).releaseHydrateLock(any());
    }

    @Test
    @DisplayName("두 번째 hydrate lock 획득 중 예외가 발생하면 먼저 잡은 lock을 해제하고 예외를 전파한다")
    void tryAcquireHydrateLocks_releasesFirstLockWhenSecondThrows() {
        TrafficLuaScriptInfraService spyService = spy(service);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofMillis(10_000L))))
                .thenReturn(true)
                .thenThrow(new QueryTimeoutException("timeout"));
        doReturn(true).when(spyService).releaseHydrateLock(any());

        assertThrows(
                ApplicationException.class,
                () -> spyService.tryAcquireHydrateLocks("indiv-lock:11", "shared-lock:22")
        );

        verify(spyService).releaseHydrateLock(any());
    }
}
