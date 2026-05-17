package com.pooli.traffic.service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.pooli.traffic.service.outbox.strategy.OutboxEventRetryStrategy;
import com.pooli.traffic.service.outbox.strategy.OutboxRetryStrategyRegistry;

@ExtendWith(MockitoExtension.class)
class RedisOutboxRetrySchedulerTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private OutboxRetryStrategyRegistry outboxRetryStrategyRegistry;

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
    @DisplayName("재시도 cap 초과 시 FINAL_FAIL로 종결하고 retry_count를 증가시키지 않는다")
    void marksFinalFailWhenRetryCapExceeded() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(301L)
                .eventType(OutboxEventType.SYNC_POLICY_ACTIVATION)
                .retryCount(10)
                .build();

        when(redisOutboxRecordService.lockRetryCandidatesAndMarkProcessing(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(record));

        redisOutboxRetryScheduler.runRetryCycle();

        verify(redisOutboxRecordService).markFinalFail(301L);
        verify(redisOutboxRecordService, never()).markFailWithRetryIncrement(anyLong());
    }

    @Test
    @DisplayName("DELETE_IN_FLIGHT_DEDUPE_KEY 재시도 성공 시 SUCCESS로 전이한다")
    void marksSuccessWhenDeleteInFlightDedupeKeyRetrySucceeds() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(401L)
                .eventType(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY)
                .retryCount(0)
                .build();

        when(redisOutboxRecordService.lockRetryCandidatesAndMarkProcessing(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(record));
        when(outboxRetryStrategyRegistry.get(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY))
                .thenReturn(outboxEventRetryStrategy);
        when(outboxEventRetryStrategy.execute(record)).thenReturn(OutboxRetryResult.SUCCESS);

        redisOutboxRetryScheduler.runRetryCycle();

        verify(redisOutboxRecordService).markSuccess(401L);
        verify(redisOutboxRecordService, never()).markFailWithRetryIncrement(anyLong());
    }

    @Test
    @DisplayName("DELETE_IN_FLIGHT_DEDUPE_KEY 재시도 실패 시 retry_count를 증가시키며 FAIL로 전이한다")
    void marksFailWithRetryIncrementWhenDeleteInFlightDedupeKeyRetryFails() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(402L)
                .eventType(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY)
                .retryCount(1)
                .build();

        when(redisOutboxRecordService.lockRetryCandidatesAndMarkProcessing(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(record));
        when(outboxRetryStrategyRegistry.get(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY))
                .thenReturn(outboxEventRetryStrategy);
        when(outboxEventRetryStrategy.execute(record)).thenReturn(OutboxRetryResult.FAIL);

        redisOutboxRetryScheduler.runRetryCycle();

        verify(redisOutboxRecordService).markFailWithRetryIncrement(402L);
        verify(redisOutboxRecordService, never()).markSuccess(anyLong());
    }
}
