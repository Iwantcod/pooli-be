package com.pooli.traffic.service.outbox.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.InFlightDedupeDeleteOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

@ExtendWith(MockitoExtension.class)
class DeleteInFlightDedupeKeyOutboxStrategyTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @InjectMocks
    private DeleteInFlightDedupeKeyOutboxStrategy deleteInFlightDedupeKeyOutboxStrategy;

    @Test
    @DisplayName("유효 payload이면 dedupe key 삭제를 수행하고 SUCCESS를 반환한다")
    void returnsSuccessWhenDeleteSucceeds() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(10L)
                .payload("{\"uuid\":\"trace-001\"}")
                .build();
        when(redisOutboxRecordService.readPayload(record, InFlightDedupeDeleteOutboxPayload.class))
                .thenReturn(InFlightDedupeDeleteOutboxPayload.builder().uuid("trace-001").build());

        OutboxRetryResult result = deleteInFlightDedupeKeyOutboxStrategy.execute(record);

        assertEquals(OutboxRetryResult.SUCCESS, result);
        verify(trafficInFlightDedupeService).delete("trace-001");
    }

    @Test
    @DisplayName("payload traceId가 비어 있으면 FAIL을 반환한다")
    void returnsFailWhenTraceIdMissing() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(11L)
                .payload("{\"uuid\":\" \"}")
                .build();
        when(redisOutboxRecordService.readPayload(record, InFlightDedupeDeleteOutboxPayload.class))
                .thenReturn(InFlightDedupeDeleteOutboxPayload.builder().uuid(" ").build());

        OutboxRetryResult result = deleteInFlightDedupeKeyOutboxStrategy.execute(record);

        assertEquals(OutboxRetryResult.FAIL, result);
        verify(trafficInFlightDedupeService, never()).delete(" ");
    }

    @Test
    @DisplayName("삭제 중 Redis 예외가 발생하면 FAIL을 반환한다")
    void returnsFailWhenDeleteThrows() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(12L)
                .payload("{\"uuid\":\"trace-err\"}")
                .build();
        when(redisOutboxRecordService.readPayload(record, InFlightDedupeDeleteOutboxPayload.class))
                .thenReturn(InFlightDedupeDeleteOutboxPayload.builder().uuid("trace-err").build());
        doThrow(new RuntimeException("redis timeout"))
                .when(trafficInFlightDedupeService)
                .delete("trace-err");

        OutboxRetryResult result = deleteInFlightDedupeKeyOutboxStrategy.execute(record);

        assertEquals(OutboxRetryResult.FAIL, result);
    }
}
