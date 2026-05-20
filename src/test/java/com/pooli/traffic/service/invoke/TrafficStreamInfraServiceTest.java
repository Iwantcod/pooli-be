package com.pooli.traffic.service.invoke;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
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
    private StreamOperations<String, Object, Object> streamOperations;

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

    @Test
    @DisplayName("ACK 성공 후 같은 record id를 stream에서 삭제한다")
    void deleteRecordAfterAckSucceeds() {
        RecordId recordId = RecordId.of("1-0");
        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId))
                .thenReturn(1L);
        when(streamOperations.delete("traffic:deduct:request", recordId))
                .thenReturn(1L);

        long acknowledged = trafficStreamInfraService.acknowledge(recordId);

        Assertions.assertThat(acknowledged).isEqualTo(1L);
        InOrder inOrder = inOrder(streamOperations);
        inOrder.verify(streamOperations).acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId);
        inOrder.verify(streamOperations).delete("traffic:deduct:request", recordId);
    }

    @Test
    @DisplayName("ACK 결과가 0이면 stream 삭제를 시도하지 않는다")
    void doNotDeleteRecordWhenAckReturnsZero() {
        RecordId recordId = RecordId.of("1-0");
        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId))
                .thenReturn(0L);

        long acknowledged = trafficStreamInfraService.acknowledge(recordId);

        Assertions.assertThat(acknowledged).isZero();
        verify(streamOperations, never()).delete("traffic:deduct:request", recordId);
    }

    @Test
    @DisplayName("XDEL 실패는 ACK 결과 반환을 실패시키지 않는다")
    void keepAckResultWhenDeleteFails() {
        RecordId recordId = RecordId.of("1-0");
        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId))
                .thenReturn(1L);
        when(streamOperations.delete("traffic:deduct:request", recordId))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));

        long acknowledged = trafficStreamInfraService.acknowledge(recordId);

        Assertions.assertThat(acknowledged).isEqualTo(1L);
        verify(streamOperations).delete("traffic:deduct:request", recordId);
    }

    @Test
    @DisplayName("XDEL 결과가 0이어도 ACK 결과 반환을 유지한다")
    void keepAckResultWhenDeleteReturnsZero() {
        RecordId recordId = RecordId.of("1-0");
        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId))
                .thenReturn(1L);
        when(streamOperations.delete("traffic:deduct:request", recordId))
                .thenReturn(0L);

        long acknowledged = trafficStreamInfraService.acknowledge(recordId);

        Assertions.assertThat(acknowledged).isEqualTo(1L);
        verify(streamOperations).delete("traffic:deduct:request", recordId);
    }

    @Test
    @DisplayName("XACK 실패는 호출부로 전파한다")
    void propagateAckFailure() {
        RecordId recordId = RecordId.of("1-0");
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Redis unavailable");
        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("traffic:deduct:request", "traffic-deduct-cg", recordId))
                .thenThrow(failure);

        assertThatThrownBy(() -> trafficStreamInfraService.acknowledge(recordId))
                .isSameAs(failure);

        verify(streamOperations, never()).delete("traffic:deduct:request", recordId);
    }
}
