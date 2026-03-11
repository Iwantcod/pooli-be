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

    /**
     * Streams 전용 RedisTemplate에서 StreamOperations 핸들을 반환합니다.
     *
     * <p>이 메서드로 스트림 API 접근점을 일원화해 테스트/유지보수를 단순화합니다.
     */
    private StreamOperations<String, String, String> streamOps() {
        return streamsStringRedisTemplate.opsForStream();
    }

    /**
     * 요청 스트림에 Consumer Group이 존재하도록 보장합니다.
     *
     * <p>이미 그룹이 있으면(BUSYGROUP) 정상 케이스로 간주하고 통과합니다.
     * 그 외 예외는 외부 시스템 오류로 전환해 상위 부팅/시작 로직에서 처리하게 합니다.
     */
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

    /**
     * Consumer Group 기준으로 스트림 메시지를 BLOCK 모드로 읽습니다.
     *
     * <p>설정값(`readCount`, `blockMs`, `group`, `consumerName`)을 사용해
     * `XREADGROUP ... BLOCK`에 대응하는 동작을 수행합니다.
     *
     * @return 읽은 레코드 목록(없으면 빈 리스트)
     */
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

    /**
     * 지정한 레코드를 현재 Consumer Group에서 ACK 처리합니다.
     *
     * @param recordId ACK 대상 레코드 ID
     * @return ACK 처리 건수
     */
    public long acknowledge(RecordId recordId) {
        return streamOps().acknowledge(
                appStreamsProperties.getKeyTrafficRequest(),
                appStreamsProperties.getGroupTraffic(),
                recordId
        );
    }

    /**
     * 현재 Consumer Group의 pending 메시지 목록을 조회합니다.
     *
     * @param count 최대 조회 개수(1 미만이면 1로 보정)
     * @return pending 메시지 목록(없으면 빈 리스트)
     */
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

    /**
     * 지정한 pending 레코드들을 현재 consumer로 claim 합니다.
     *
     * <p>reclaim 루프에서 idle 시간이 충분한 메시지를 현재 워커로 되가져올 때 사용합니다.
     *
     * @param recordIds claim 대상 레코드 ID 목록
     * @param minIdleMs claim 허용 최소 idle 시간(ms)
     * @return claim 성공 레코드 목록(없으면 빈 리스트)
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

    /**
     * 레코드 ID 하나를 기준으로 원본 요청 스트림에서 단건 조회합니다.
     *
     * @param recordId 조회할 레코드 ID
     * @return 레코드가 있으면 해당 값, 없으면 null
     */
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

    /**
     * 처리 실패 메시지를 DLQ 스트림에 기록합니다.
     *
     * <p>기록 필드:
     * - payload: 원본 payload JSON(없으면 빈 문자열)
     * - reason: 실패 사유
     * - sourceRecordId: 원본 스트림 레코드 ID
     * - failedAt: 실패 시각(epoch millis)
     *
     * @param payload 원본 payload
     * @param reason 실패 사유
     * @param sourceRecordId 원본 레코드 ID
     * @return DLQ에 기록된 RecordId
     */
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

    /**
     * Streams 레코드 값 맵에서 payload 필드만 추출합니다.
     *
     * @param record Streams 레코드
     * @return payload 문자열(없으면 null)
     */
    public String extractPayload(MapRecord<String, String, String> record) {
        return record.getValue().get(TrafficStreamFields.PAYLOAD);
    }

    /**
     * Consumer Group 생성 예외가 "이미 그룹이 존재"하는 케이스인지 판별합니다.
     *
     * <p>라이브러리/드라이버별 예외 타입 차이를 흡수하기 위해 메시지(BUSYGROUP)와
     * 예외 클래스명(RedisBusyException)을 모두 확인합니다.
     *
     * @param throwable 판별할 예외
     * @return 이미 그룹이 존재하는 예외면 true
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
     * Consumer Group 읽기 호출을 공통화한 내부 헬퍼입니다.
     *
     * <p>StreamOffset 가변 인자는 제네릭 배열 경고를 유발할 수 있어 `@SafeVarargs`를 적용합니다.
     * 내부에서 인자를 변경하지 않고 그대로 전달만 하므로 안전합니다.
     *
     * @param consumer 읽기 대상 consumer
     * @param options 읽기 옵션(count/block)
     * @param streamOffsets 읽을 스트림/오프셋 목록
     * @return 조회된 레코드 목록(라이브러리 반환값 그대로)
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
