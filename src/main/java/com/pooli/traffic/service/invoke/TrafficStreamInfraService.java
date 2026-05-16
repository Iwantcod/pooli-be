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
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.FailureKind;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.RedisTarget;
import com.pooli.traffic.domain.TrafficStreamFields;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

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
    private final TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    private StreamOperations<String, String, String> streamOps() {
        return streamsStringRedisTemplate.opsForStream();
    }

    /**
     * 트래픽 요청 Stream의 consumer group을 준비합니다.
     *
     * <p>1. 설정에서 stream key, group, consumer 이름을 검증해 읽습니다.
     * <br>2. Redis `XGROUP CREATE ... MKSTREAM`으로 stream이 없으면 생성까지 함께 수행합니다.
     * <br>3. 이미 존재하는 BUSYGROUP 오류는 정상 부팅 상태로 보고 무시합니다.
     */
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

    /**
     * 기본 read count 설정으로 Stream record를 blocking read합니다.
     */
    public List<MapRecord<String, String, String>> readBlocking() {
        return readBlocking(appStreamsProperties.getReadCount());
    }

    /**
     * Redis Stream에서 처리할 record를 consumer group 기준으로 읽습니다.
     *
     * <p>1. 요청 count와 block timeout으로 read option을 구성합니다.
     * <br>2. 현재 worker consumer 이름으로 `lastConsumed` 이후 record를 조회합니다.
     * <br>3. Redis 호출은 `executeStreamsOperation()`으로 감싸 요청/실패 metric을 함께 기록합니다.
     * <br>4. Redis가 null을 반환하면 호출부가 안전하게 순회할 수 있도록 빈 리스트를 반환합니다.
     */
    public List<MapRecord<String, String, String>> readBlocking(int requestedCount) {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(Math.max(1, requestedCount))
                .block(Duration.ofMillis(appStreamsProperties.requireBlockMs()));

        String group = appStreamsProperties.requireTrafficGroup();
        String consumerName = appStreamsProperties.requireConsumerNameForBootstrap();
        String streamKey = appStreamsProperties.requireTrafficRequestStreamKey();

        Consumer consumer = Consumer.from(group, consumerName);

        List<MapRecord<String, String, String>> records = executeStreamsOperation(() ->
                readGroupRecords(
                        consumer,
                        options,
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                )
        );

        if (records == null) {
            return List.of();
        }

        return records;
    }

    /**
     * 처리 완료된 Stream record를 ACK 처리합니다.
     *
     * <p>1. traffic request stream key와 group 이름으로 `XACK`를 실행합니다.
     * <br>2. Redis 호출 시도/실패 metric을 streams Redis tag로 기록합니다.
     * <br>3. Redis가 null을 반환하면 ACK 건수 0으로 보정합니다.
     */
    public long acknowledge(RecordId recordId) {
        Long acknowledged = executeStreamsOperation(() ->
                streamOps().acknowledge(
                        appStreamsProperties.getKeyTrafficRequest(),
                        appStreamsProperties.getGroupTraffic(),
                        recordId
                )
        );
        return acknowledged == null ? 0L : acknowledged;
    }

    /**
     * pending 상태 record 목록을 조회합니다.
     *
     * <p>1. 조회 count는 최소 1 이상으로 보정합니다.
     * <br>2. consumer group의 pending 범위를 unbounded로 조회합니다.
     * <br>3. Redis 호출 metric을 기록한 뒤 결과를 일반 List로 변환해 반환합니다.
     */
    public List<PendingMessage> readPendingMessages(long count) {
        long safeCount = Math.max(1L, count);
        PendingMessages pendingMessages = executeStreamsOperation(() ->
                streamOps().pending(
                        appStreamsProperties.getKeyTrafficRequest(),
                        appStreamsProperties.getGroupTraffic(),
                        Range.unbounded(),
                        safeCount
                )
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

    /**
     * reclaim 대상 pending record를 현재 consumer로 claim합니다.
     *
     * <p>1. claim할 record id가 없으면 Redis 호출 없이 빈 리스트를 반환합니다.
     * <br>2. min idle 값을 음수가 되지 않도록 보정해 `XCLAIM` 옵션을 구성합니다.
     * <br>3. Redis 호출 metric을 기록하고 null 결과는 빈 리스트로 보정합니다.
     */
    public List<MapRecord<String, String, String>> claimPending(
            List<RecordId> recordIds,
            long minIdleMs
    ) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }

        XClaimOptions claimOptions = XClaimOptions.minIdleMs(Math.max(0L, minIdleMs))
                .ids(recordIds.toArray(RecordId[]::new));

        List<MapRecord<String, String, String>> claimed = executeStreamsOperation(() ->
                streamOps().claim(
                        appStreamsProperties.getKeyTrafficRequest(),
                        appStreamsProperties.getGroupTraffic(),
                        appStreamsProperties.requireConsumerNameForBootstrap(),
                        claimOptions
                )
        );

        if (claimed == null) {
            return List.of();
        }
        return claimed;
    }

    /**
     * 특정 Stream record id의 원본 record를 조회합니다.
     *
     * <p>1. record id가 없으면 Redis 호출 없이 null을 반환합니다.
     * <br>2. id와 같은 closed range를 count 1로 조회합니다.
     * <br>3. Redis 호출 metric을 기록하고 조회 결과가 없으면 null을 반환합니다.
     */
    public MapRecord<String, String, String> readRecordById(RecordId recordId) {
        if (recordId == null) {
            return null;
        }

        List<MapRecord<String, String, String>> records = executeStreamsOperation(() ->
                streamOps().range(
                        appStreamsProperties.getKeyTrafficRequest(),
                        Range.closed(recordId.getValue(), recordId.getValue()),
                        Limit.limit().count(1)
                )
        );

        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.get(0);
    }

    /**
     * 처리 불가 record를 DLQ Stream에 기록합니다.
     *
     * <p>1. 원본 payload, 실패 사유, 원본 record id, 실패 시각을 DLQ 필드로 구성합니다.
     * <br>2. Redis Stream `ADD` 호출을 metric wrapper 안에서 실행합니다.
     * <br>3. DLQ 사유별 application metric을 증가시키고 생성된 DLQ record id를 반환합니다.
     */
    public RecordId writeDlq(String payload, String reason, String sourceRecordId) {
        Map<String, String> dlqValue = new HashMap<>();
        dlqValue.put(TrafficStreamFields.PAYLOAD, payload == null ? "" : payload);
        dlqValue.put("reason", reason);
        dlqValue.put("sourceRecordId", sourceRecordId);
        dlqValue.put("failedAt", String.valueOf(System.currentTimeMillis()));

        RecordId recordId = executeStreamsOperation(() ->
                streamOps().add(
                        StreamRecords.string(dlqValue)
                                .withStreamKey(appStreamsProperties.getKeyTrafficDlq())
                )
        );

        trafficDlqMetrics.incrementDlqByReason(reason);

        return recordId;
    }

    /**
     * Stream record에서 traffic payload 문자열을 꺼냅니다.
     */
    public String extractPayload(MapRecord<String, String, String> record) {
        return record.getValue().get(TrafficStreamFields.PAYLOAD);
    }

    /**
     * Redis consumer group을 `MKSTREAM` 옵션으로 생성합니다.
     *
     * <p>1. stream key를 Redis serializer로 byte 배열로 변환합니다.
     * <br>2. bootstrap offset `0-0` 기준으로 consumer group을 생성합니다.
     * <br>3. Redis가 null을 반환하면 bootstrap 실패로 판단해 전용 예외를 던집니다.
     */
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

    /**
     * Stream key를 Redis connection API가 요구하는 byte 배열로 직렬화합니다.
     *
     * <p>1. 현재 RedisTemplate의 String serializer를 사용합니다.
     * <br>2. serializer가 null을 반환하면 Redis 명령을 안전하게 만들 수 없으므로 bootstrap 예외를 던집니다.
     */
    private byte[] serializeStreamKey(String streamKey) {
        RedisSerializer<String> serializer = streamsStringRedisTemplate.getStringSerializer();
        byte[] serialized = serializer.serialize(streamKey);
        if (serialized == null) {
            throw new TrafficStreamBootstrapException("Failed to serialize Redis stream key for consumer bootstrap.");
        }
        return serialized;
    }

    /**
     * consumer group 중복 생성 오류인지 cause chain 기준으로 판정합니다.
     *
     * <p>1. Spring/Lettuce 래핑 예외를 고려해 cause chain을 순회합니다.
     * <br>2. Redis BUSYGROUP 메시지 또는 busy exception type이면 이미 생성된 상태로 판단합니다.
     * <br>3. 그 외 예외는 bootstrap 실패로 전파되도록 false를 반환합니다.
     */
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

    /**
     * streams Redis 작업을 실행하면서 가용성 raw metric을 함께 기록합니다.
     *
     * <p>1. Redis 명령 실행 직전에 `traffic_redis_ops_total{redis="streams"}`를 증가시킵니다.
     * <br>2. 작업이 성공하면 원래 결과를 그대로 반환합니다.
     * <br>3. RuntimeException이 발생하면 실패 유형을 분류해 `traffic_redis_failures_total`을 증가시키고 예외를 재전파합니다.
     */
    private <T> T executeStreamsOperation(StreamsOperation<T> operation) {
        trafficRedisAvailabilityMetrics.incrementOperation(RedisTarget.STREAMS);
        try {
            return operation.execute();
        } catch (RuntimeException e) {
            trafficRedisAvailabilityMetrics.incrementFailure(RedisTarget.STREAMS, resolveFailureKind(e));
            throw e;
        }
    }

    /**
     * Redis 예외를 EM6 alert rule tag 계약에 맞는 실패 유형으로 변환합니다.
     *
     * <p>1. timeout 계열이면 `timeout`으로 분류합니다.
     * <br>2. connection 계열이면 `connection`으로 분류합니다.
     * <br>3. 둘 다 아니면 가용성 실패율 계산에서 제외할 `non_retryable`로 분류합니다.
     */
    private FailureKind resolveFailureKind(RuntimeException failure) {
        if (trafficRedisFailureClassifier.isTimeoutFailure(failure)) {
            return FailureKind.TIMEOUT;
        }
        if (trafficRedisFailureClassifier.isConnectionFailure(failure)) {
            return FailureKind.CONNECTION;
        }
        return FailureKind.NON_RETRYABLE;
    }

    /**
     * metric wrapper가 Redis 작업을 지연 실행하기 위한 내부 함수형 인터페이스입니다.
     */
    @FunctionalInterface
    private interface StreamsOperation<T> {
        T execute();
    }

    /**
     * varargs StreamOffset 경고를 숨기기 위해 Stream read 호출을 별도 메서드로 분리합니다.
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
