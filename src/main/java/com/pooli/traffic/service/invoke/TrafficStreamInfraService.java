package com.pooli.traffic.service.invoke;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import com.pooli.common.config.AppStreamsProperties;
import com.pooli.monitoring.metrics.TrafficDlqMetrics;
import com.pooli.traffic.domain.TrafficStreamFields;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamInfraService {
    private static final ReadOffset CONSUMER_GROUP_BOOTSTRAP_OFFSET = ReadOffset.from("0-0");

    @Qualifier("streamsStringRedisTemplate")
    private final StringRedisTemplate streamsStringRedisTemplate;
    private final AppStreamsProperties appStreamsProperties;
    private final TrafficDlqMetrics trafficDlqMetrics;

    private StreamOperations<String, String, String> streamOps() {
        return streamsStringRedisTemplate.opsForStream();
    }

    public void ensureConsumerGroup() {
        String streamKey = appStreamsProperties.requireTrafficRequestStreamKey();
        String group = appStreamsProperties.requireTrafficGroup();
        String consumerName = appStreamsProperties.requireConsumerNameForBootstrap();

        try {
            createConsumerGroupWithMkStream(streamKey, group);
            log.info(
                    "traffic_stream_group_created streamKey={} group={} consumer={} bootstrapOffset={}",
                    streamKey,
                    group,
                    consumerName,
                    CONSUMER_GROUP_BOOTSTRAP_OFFSET.getOffset()
            );
        } catch (DataAccessException e) {
            if (isBusyGroupError(e)) {
                log.info(
                        "traffic_stream_group_exists streamKey={} group={} consumer={} bootstrapOffset={}",
                        streamKey,
                        group,
                        consumerName,
                        CONSUMER_GROUP_BOOTSTRAP_OFFSET.getOffset()
                );
                return;
            }

            log.error("traffic_stream_group_create_failed streamKey={} group={} consumer={}", streamKey, group, consumerName, e);
            throw new TrafficStreamBootstrapException(
                    String.format(
                            "Failed to bootstrap traffic stream consumer group. streamKey=%s, group=%s, consumer=%s",
                            streamKey,
                            group,
                            consumerName
                    ),
                    e
            );
        }
    }

    public List<MapRecord<String, String, String>> readBlocking() {
        return readBlocking(appStreamsProperties.getReadCount());
    }

    public List<MapRecord<String, String, String>> readBlocking(int requestedCount) {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(Math.max(1, requestedCount))
                .block(Duration.ofMillis(appStreamsProperties.getBlockMs()));

        String group = appStreamsProperties.requireTrafficGroup();
        String consumerName = appStreamsProperties.requireConsumerNameForBootstrap();
        String streamKey = appStreamsProperties.requireTrafficRequestStreamKey();

        Consumer consumer = Consumer.from(group, consumerName);

        List<MapRecord<String, String, String>> records = readGroupRecords(
                consumer,
                options,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        if (records == null) {
            return List.of();
        }

        return records;
    }

    public long acknowledge(RecordId recordId) {
        return streamOps().acknowledge(
                appStreamsProperties.getKeyTrafficRequest(),
                appStreamsProperties.getGroupTraffic(),
                recordId
        );
    }

    public List<PendingMessage> readPendingMessages(long count) {
        long safeCount = Math.max(1L, count);
        PendingMessages pendingMessages = streamOps().pending(
                appStreamsProperties.getKeyTrafficRequest(),
                appStreamsProperties.getGroupTraffic(),
                Range.unbounded(),
                safeCount
        );

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return List.of();
        }

        List<PendingMessage> messages = new ArrayList<>(pendingMessages.size());
        for (PendingMessage pendingMessage : pendingMessages) {
            messages.add(pendingMessage);
        }
        return messages;
    }

    public List<MapRecord<String, String, String>> claimPending(
            List<RecordId> recordIds,
            long minIdleMs
    ) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }

        XClaimOptions claimOptions = XClaimOptions.minIdleMs(Math.max(0L, minIdleMs))
                .ids(recordIds.toArray(RecordId[]::new));

        List<MapRecord<String, String, String>> claimed = streamOps().claim(
                appStreamsProperties.getKeyTrafficRequest(),
                appStreamsProperties.getGroupTraffic(),
                appStreamsProperties.requireConsumerNameForBootstrap(),
                claimOptions
        );

        if (claimed == null) {
            return List.of();
        }
        return claimed;
    }

    public MapRecord<String, String, String> readRecordById(RecordId recordId) {
        if (recordId == null) {
            return null;
        }

        List<MapRecord<String, String, String>> records = streamOps().range(
                appStreamsProperties.getKeyTrafficRequest(),
                Range.closed(recordId.getValue(), recordId.getValue()),
                Limit.limit().count(1)
        );

        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.get(0);
    }

    public RecordId writeDlq(String payload, String reason, String sourceRecordId) {
        Map<String, String> dlqValue = new HashMap<>();
        dlqValue.put(TrafficStreamFields.PAYLOAD, payload == null ? "" : payload);
        dlqValue.put("reason", reason);
        dlqValue.put("sourceRecordId", sourceRecordId);
        dlqValue.put("failedAt", String.valueOf(System.currentTimeMillis()));

        RecordId recordId = streamOps().add(
                StreamRecords.string(dlqValue)
                        .withStreamKey(appStreamsProperties.getKeyTrafficDlq())
        );

        trafficDlqMetrics.incrementDlqByReason(reason);

        return recordId;
    }

    public String extractPayload(MapRecord<String, String, String> record) {
        return record.getValue().get(TrafficStreamFields.PAYLOAD);
    }

    private void createConsumerGroupWithMkStream(String streamKey, String group) {
        String result = streamsStringRedisTemplate.execute((RedisCallback<String>) connection ->
                connection.streamCommands().xGroupCreate(
                        serializeStreamKey(streamKey),
                        group,
                        CONSUMER_GROUP_BOOTSTRAP_OFFSET,
                        true
                )
        );

        if (result == null) {
            throw new TrafficStreamBootstrapException(
                    String.format("Redis returned null while creating consumer group. streamKey=%s, group=%s", streamKey, group)
            );
        }
    }

    private byte[] serializeStreamKey(String streamKey) {
        RedisSerializer<String> serializer = streamsStringRedisTemplate.getStringSerializer();
        byte[] serialized = serializer.serialize(streamKey);
        if (serialized == null) {
            throw new TrafficStreamBootstrapException("Failed to serialize Redis stream key for consumer bootstrap.");
        }
        return serialized;
    }

    private boolean isBusyGroupError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }

            String exceptionType = current.getClass().getName();
            if (exceptionType.contains("RedisBusyException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SafeVarargs
    private final List<MapRecord<String, String, String>> readGroupRecords(
            Consumer consumer,
            StreamReadOptions options,
            StreamOffset<String>... streamOffsets
    ) {
        return streamOps().read(consumer, options, streamOffsets);
    }
}
