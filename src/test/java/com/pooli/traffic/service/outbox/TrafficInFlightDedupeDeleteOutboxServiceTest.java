package com.pooli.traffic.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.service.retry.TrafficInFlightDedupeDeleteRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficInFlightDedupeDeleteRetryInvoker;

@ExtendWith(MockitoExtension.class)
class TrafficInFlightDedupeDeleteOutboxServiceTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private TrafficInFlightDedupeDeleteRetryInvoker trafficInFlightDedupeDeleteRetryInvoker;

    @InjectMocks
    private TrafficInFlightDedupeDeleteOutboxService trafficInFlightDedupeDeleteOutboxService;

    @Test
    @DisplayName("즉시 삭제가 1회차에 성공하면 SUCCESS로 종료한다")
    void markSuccessWhenImmediateDeleteSucceeds() {
        when(redisOutboxRecordService.createPending(eq(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY), any(), eq("trace-001")))
                .thenReturn(101L);
        when(trafficInFlightDedupeDeleteRetryInvoker.delete("trace-001"))
                .thenReturn(TrafficInFlightDedupeDeleteRetryExecutionResult.success(1, null));

        long outboxId = trafficInFlightDedupeDeleteOutboxService.createPending("trace-001", "1-0");

        assertEquals(101L, outboxId);
        verify(trafficInFlightDedupeDeleteRetryInvoker).delete("trace-001");
        verify(redisOutboxRecordService).markSuccess(101L);
        verify(redisOutboxRecordService, never()).markFail(anyLong());
    }

    @Test
    @DisplayName("즉시 삭제가 일부 실패 후 복구되면 SUCCESS로 종료한다")
    void markSuccessWhenImmediateDeleteRecoversAfterFailures() {
        when(redisOutboxRecordService.createPending(eq(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY), any(), eq("trace-002")))
                .thenReturn(102L);
        when(trafficInFlightDedupeDeleteRetryInvoker.delete("trace-002"))
                .thenReturn(TrafficInFlightDedupeDeleteRetryExecutionResult.success(
                        3,
                        new RuntimeException("redis timeout")
                ));

        long outboxId = trafficInFlightDedupeDeleteOutboxService.createPending("trace-002", "2-0");

        assertEquals(102L, outboxId);
        verify(trafficInFlightDedupeDeleteRetryInvoker).delete("trace-002");
        verify(redisOutboxRecordService).markSuccess(102L);
        verify(redisOutboxRecordService, never()).markFail(anyLong());
    }

    @Test
    @DisplayName("초기 시도 실패 후 즉시 재시도 3회를 모두 소진하면 FAIL로 종료한다")
    void markFailWhenImmediateDeleteExhausted() {
        when(redisOutboxRecordService.createPending(eq(OutboxEventType.DELETE_IN_FLIGHT_DEDUPE_KEY), any(), eq("trace-003")))
                .thenReturn(103L);
        when(trafficInFlightDedupeDeleteRetryInvoker.delete("trace-003"))
                .thenReturn(TrafficInFlightDedupeDeleteRetryExecutionResult.failure(
                        new RuntimeException("redis timeout"),
                        4
                ));

        long outboxId = trafficInFlightDedupeDeleteOutboxService.createPending("trace-003", "3-0");

        assertEquals(103L, outboxId);
        verify(trafficInFlightDedupeDeleteRetryInvoker).delete("trace-003");
        verify(redisOutboxRecordService).markFail(103L);
        verify(redisOutboxRecordService, never()).markSuccess(anyLong());
        verify(redisOutboxRecordService, never()).markFailWithRetryIncrement(anyLong());
    }

    @Test
    @DisplayName("traceId가 비어 있으면 outbox 적재를 차단한다")
    void rejectBlankTraceId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> trafficInFlightDedupeDeleteOutboxService.createPending("   ", "4-0")
        );

        verify(redisOutboxRecordService, never()).createPending(any(), any(), any());
        verify(trafficInFlightDedupeDeleteRetryInvoker, never()).delete(any());
    }
}
