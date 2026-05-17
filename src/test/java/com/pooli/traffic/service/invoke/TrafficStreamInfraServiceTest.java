package com.pooli.traffic.service.invoke;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import com.pooli.common.config.AppStreamsProperties;
import com.pooli.monitoring.metrics.TrafficDlqMetrics;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

@ExtendWith(MockitoExtension.class)
class TrafficStreamInfraServiceTest {

    @Mock
    private StringRedisTemplate streamsStringRedisTemplate;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private RedisStreamCommands redisStreamCommands;

    @Mock
    private TrafficDlqMetrics trafficDlqMetrics;

    @Mock
    private TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    private AppStreamsProperties appStreamsProperties;
    private TrafficStreamInfraService trafficStreamInfraService;

    @BeforeEach
    void setUp() {
        appStreamsProperties = new AppStreamsProperties();
        appStreamsProperties.setKeyTrafficRequest("traffic:deduct:request");
        appStreamsProperties.setGroupTraffic("traffic-deduct-cg");
        appStreamsProperties.setConsumerName("traffic-node-a");

        trafficStreamInfraService = new TrafficStreamInfraService(
                streamsStringRedisTemplate,
                appStreamsProperties,
                trafficDlqMetrics,
                trafficRedisAvailabilityMetrics,
                trafficRedisFailureClassifier
        );

        lenient().when(streamsStringRedisTemplate.getStringSerializer()).thenReturn(RedisSerializer.string());
        lenient().when(streamsStringRedisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<String> callback = invocation.getArgument(0);
            return callback.doInRedis(redisConnection);
        });
        lenient().when(redisConnection.streamCommands()).thenReturn(redisStreamCommands);
    }

    @Test
    @DisplayName("creates consumer group with MKSTREAM and backlog bootstrap offset when stream is missing")
    void createConsumerGroupWithMkStreamWhenStreamMissing() {
        when(redisStreamCommands.xGroupCreate(
                any(byte[].class),
                eq("traffic-deduct-cg"),
                any(ReadOffset.class),
                eq(true)
        )).thenReturn("OK");

        assertThatCode(() -> trafficStreamInfraService.ensureConsumerGroup())
                .doesNotThrowAnyException();

        verify(redisStreamCommands).xGroupCreate(
                org.mockito.ArgumentMatchers.argThat(bytes ->
                        Arrays.equals(bytes, "traffic:deduct:request".getBytes(StandardCharsets.UTF_8))
                ),
                eq("traffic-deduct-cg"),
                eq(ReadOffset.from("0-0")),
                eq(true)
        );
    }

    @Test
    @DisplayName("treats BUSYGROUP as already bootstrapped")
    void treatBusyGroupAsAlreadyBootstrapped() {
        when(redisStreamCommands.xGroupCreate(
                any(byte[].class),
                eq("traffic-deduct-cg"),
                any(ReadOffset.class),
                eq(true)
        )).thenThrow(new DataAccessResourceFailureException("BUSYGROUP Consumer Group name already exists"));

        assertThatCode(() -> trafficStreamInfraService.ensureConsumerGroup())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fails fast when consumer name is blank")
    void failFastWhenConsumerNameIsBlank() {
        appStreamsProperties.setConsumerName("   ");

        assertThatThrownBy(() -> trafficStreamInfraService.ensureConsumerGroup())
                .isInstanceOf(TrafficStreamBootstrapException.class)
                .hasMessageContaining("app.streams.consumer-name")
                .hasMessageContaining("unique per-instance");

        verify(streamsStringRedisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    @DisplayName("fails fast when consumer name uses a shared default value")
    void failFastWhenConsumerNameUsesSharedDefault() {
        appStreamsProperties.setConsumerName("traffic-consumer");

        assertThatThrownBy(() -> trafficStreamInfraService.ensureConsumerGroup())
                .isInstanceOf(TrafficStreamBootstrapException.class)
                .hasMessageContaining("shared/default value");

        verify(streamsStringRedisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    @DisplayName("wraps unexpected Redis bootstrap failure with dedicated exception")
    void wrapUnexpectedRedisBootstrapFailure() {
        when(redisStreamCommands.xGroupCreate(
                any(byte[].class),
                eq("traffic-deduct-cg"),
                any(ReadOffset.class),
                eq(true)
        )).thenThrow(new DataAccessResourceFailureException("NOAUTH Authentication required"));

        assertThatThrownBy(() -> trafficStreamInfraService.ensureConsumerGroup())
                .isInstanceOf(TrafficStreamBootstrapException.class)
                .hasMessageContaining("Failed to bootstrap traffic stream consumer group")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
