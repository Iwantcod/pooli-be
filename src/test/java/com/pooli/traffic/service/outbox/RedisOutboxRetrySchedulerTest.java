package com.pooli.traffic.service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.RefillOutboxPayload;
import com.pooli.traffic.service.outbox.strategy.OutboxEventRetryStrategy;
import com.pooli.traffic.service.outbox.strategy.OutboxRetryStrategyRegistry;

@ExtendWith(MockitoExtension.class)
class RedisOutboxRetrySchedulerTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private OutboxRetryStrategyRegistry outboxRetryStrategyRegistry;

    @Mock
    private TrafficRefillOutboxSupportService trafficRefillOutboxSupportService;

    @Mock
    private OutboxEventRetryStrategy outboxEventRetryStrategy;

    @InjectMocks
    private RedisOutboxRetryScheduler redisOutboxRetryScheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(redisOutboxRetryScheduler, "batchSize", 10);
        ReflectionTestUtils.setField(redisOutboxRetryScheduler, "pendingDelaySeconds", 1);
        ReflectionTestUtils.setField(redisOutboxRetryScheduler, "processingStuckSeconds", 1);
        ReflectionTestUtils.setField(redisOutboxRetryScheduler, "maxRetryCount", 10);
    }

    @Test
    @DisplayName("REFILL 성공 후 멱등키 정리 실패는 성공 상태를 유지한다")
    void preservesSuccessWhenIdempotencyClearFails() {
        // given
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(101L)
                .eventType(OutboxEventType.REFILL)
                .retryCount(0)
                .build();
        RefillOutboxPayload payload = RefillOutboxPayload.builder()
                .uuid("refill-uuid-1")
                .build();

        when(redisOutboxRecordService.lockRetryCandidatesAndMarkProcessing(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(record));
        when(outboxRetryStrategyRegistry.get(OutboxEventType.REFILL)).thenReturn(outboxEventRetryStrategy);
        when(outboxEventRetryStrategy.execute(record)).thenReturn(OutboxRetryResult.SUCCESS);
        when(redisOutboxRecordService.readPayload(record, RefillOutboxPayload.class)).thenReturn(payload);
        doThrow(new RuntimeException("redis down"))
                .when(trafficRefillOutboxSupportService)
                .clearIdempotency("refill-uuid-1");

        // when
        redisOutboxRetryScheduler.runRetryCycle();

        // then
        verify(redisOutboxRecordService).markSuccess(101L);
        verify(trafficRefillOutboxSupportService).clearIdempotency("refill-uuid-1");
        verify(redisOutboxRecordService, never()).markFailWithRetryIncrement(101L);
        verify(trafficRefillOutboxSupportService, never()).compensateRefillOnce(any(), any(RefillOutboxPayload.class));
    }
}
