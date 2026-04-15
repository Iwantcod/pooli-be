package com.pooli.traffic.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.mapper.RedisOutboxMapper;

@ExtendWith(MockitoExtension.class)
class RedisOutboxRecordServiceTest {

    @Mock
    private RedisOutboxMapper redisOutboxMapper;

    private RedisOutboxRecordService redisOutboxRecordService;

    @BeforeEach
    void setUp() {
        redisOutboxRecordService = new RedisOutboxRecordService(redisOutboxMapper, new ObjectMapper());
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("traceId와 MDC가 모두 비어 있으면 Outbox 생성을 차단한다")
    void throwsWhenTraceIdAndMdcAreBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> redisOutboxRecordService.createPending(
                        OutboxEventType.SYNC_POLICY_ACTIVATION,
                        Map.of("policyId", 1L),
                        "   "
                )
        );

        verify(redisOutboxMapper, never()).insert(any(RedisOutboxRecord.class));
    }

    @Test
    @DisplayName("메서드 traceId가 비어 있으면 MDC traceId를 사용한다")
    void usesMdcTraceIdWhenArgumentIsBlank() {
        MDC.put("traceId", "trace-mdc-001");
        doAnswer(invocation -> {
            RedisOutboxRecord record = invocation.getArgument(0);
            assertEquals("trace-mdc-001", record.getTraceId());
            ReflectionTestUtils.setField(record, "id", 101L);
            return 1;
        }).when(redisOutboxMapper).insert(any(RedisOutboxRecord.class));

        long outboxId = redisOutboxRecordService.createPending(
                OutboxEventType.SYNC_POLICY_ACTIVATION,
                Map.of("policyId", 1L),
                " "
        );

        assertEquals(101L, outboxId);
        verify(redisOutboxMapper).insert(any(RedisOutboxRecord.class));
    }

    @Test
    @DisplayName("메서드 traceId가 있으면 해당 값을 우선 사용한다")
    void prioritizesMethodTraceId() {
        MDC.put("traceId", "trace-mdc-should-not-be-used");
        doAnswer(invocation -> {
            RedisOutboxRecord record = invocation.getArgument(0);
            assertEquals("trace-arg-001", record.getTraceId());
            ReflectionTestUtils.setField(record, "id", 202L);
            return 1;
        }).when(redisOutboxMapper).insert(any(RedisOutboxRecord.class));

        long outboxId = redisOutboxRecordService.createPending(
                OutboxEventType.SYNC_LINE_LIMIT,
                Map.of("lineId", 11L),
                "trace-arg-001"
        );

        assertEquals(202L, outboxId);
    }
}
