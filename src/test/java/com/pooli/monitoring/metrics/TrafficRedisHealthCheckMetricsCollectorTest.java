package com.pooli.monitoring.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.RedisTarget;

@ExtendWith(MockitoExtension.class)
class TrafficRedisHealthCheckMetricsCollectorTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private StringRedisTemplate streamsStringRedisTemplate;

    @Mock
    private RedisConnection cacheConnection;

    @Mock
    private TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;

    @Test
    @DisplayName("records cache and streams PING status")
    void recordsCacheAndStreamsPingStatus() {
        when(cacheStringRedisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<String> callback = invocation.getArgument(0);
            return callback.doInRedis(cacheConnection);
        });
        when(cacheConnection.ping()).thenReturn("PONG");
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(streamsStringRedisTemplate)
                .execute(any(RedisCallback.class));

        TrafficRedisHealthCheckMetricsCollector collector = new TrafficRedisHealthCheckMetricsCollector(
                cacheStringRedisTemplate,
                streamsStringRedisTemplate,
                trafficRedisAvailabilityMetrics
        );

        collector.collect();

        verify(trafficRedisAvailabilityMetrics).recordPingResult(RedisTarget.CACHE, true);
        verify(trafficRedisAvailabilityMetrics).recordPingResult(RedisTarget.STREAMS, false);
    }
}

