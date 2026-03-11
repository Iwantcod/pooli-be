package com.pooli.traffic.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.dao.DataAccessException;
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
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.traffic.domain.TrafficStreamFields;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Streams 소비 인프라를 담당하는 서비스입니다.
 * Consumer Group 생성, BLOCK 읽기, ACK, DLQ 적재를 공통 유틸로 제공합니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamInfraService {

    @Qualifier("streamsStringRedisTemplate")
    private final StringRedisTemplate streamsStringRedisTemplate;
    private final AppStreamsProperties appStreamsProperties;

    private StreamOperations<String, String, String> streamOps() {
        return streamsStringRedisTemplate.opsForStream();
    }

    public void ensureConsumerGroup() {
        String streamKey = appStreamsProperties.getKeyTrafficRequest();
        String group = appStreamsProperties.getGroupTraffic();

        try {
            streamOps().createGroup(streamKey, ReadOffset.latest(), group);
            log.info("traffic_stream_group_created streamKey={} group={}", streamKey, group);
        } catch (DataAccessException e) {
            if (isBusyGroupError(e)) {
                log.info("traffic_stream_group_exists streamKey={} group={}", streamKey, group);
                return;
            }
            log.error("traffic_stream_group_create_failed streamKey={} group={}", streamKey, group, e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Streams consumer group 생성에 실패했습니다.");
        }
    }

    public List<MapRecord<String, String, String>> readBlocking() {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(appStreamsProperties.getReadCount())
                .block(Duration.ofMillis(appStreamsProperties.getBlockMs()));

        Consumer consumer = Consumer.from(
                appStreamsProperties.getGroupTraffic(),
                appStreamsProperties.getConsumerName()
        );

        List<MapRecord<String, String, String>> records = readGroupRecords(
                consumer,
                options,
                StreamOffset.create(appStreamsProperties.getKeyTrafficRequest(), ReadOffset.lastConsumed())
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
                appStreamsProperties.getConsumerName(),
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

        return streamOps().add(
                StreamRecords.string(dlqValue)
                        .withStreamKey(appStreamsProperties.getKeyTrafficDlq())
        );
    }

    public String extractPayload(MapRecord<String, String, String> record) {
        return record.getValue().get(TrafficStreamFields.PAYLOAD);
    }

    private boolean isBusyGroupError(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }

    /**
     * StreamOffset 가변 인자는 제네릭 배열 경고를 유발할 수 있어 @SafeVarargs 로 감싼다.
     * 내부에서 인자를 변경하지 않고 그대로 전달만 하므로 varargs 사용이 안전하다.
     */
    @SafeVarargs
    private final List<MapRecord<String, String, String>> readGroupRecords(
            Consumer consumer,
            StreamReadOptions options,
            StreamOffset<String>... streamOffsets
    ) {
        return streamOps().read(consumer, options, streamOffsets);
    }
}
