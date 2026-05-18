package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.service.invoke.TrafficDeductCompletionPersistenceService.CompletionPersistenceResult;
import com.pooli.traffic.service.outbox.TrafficInFlightDedupeDeleteOutboxService;

@ExtendWith(MockitoExtension.class)
class TrafficDeductCompletionPersistenceServiceTest {

    @Mock
    private TrafficDeductDoneLogService trafficDeductDoneLogService;

    @Mock
    private TrafficInFlightDedupeDeleteOutboxService trafficInFlightDedupeDeleteOutboxService;

    @InjectMocks
    private TrafficDeductCompletionPersistenceService trafficDeductCompletionPersistenceService;

    @Nested
    @DisplayName("persistCompletion 테스트")
    class PersistCompletionTest {

        @Test
        @DisplayName("done log 신규 저장 후 outbox PENDING을 같은 흐름에서 생성한다")
        void createsDeferredOutboxAfterNewDoneLogSave() {
            TrafficPayloadReqDto payload = payload("trace-001");
            TrafficDeductResultResDto result = result();

            when(trafficDeductDoneLogService.saveIfAbsent(payload, result, "1-0", 10L))
                    .thenReturn(true);
            when(trafficInFlightDedupeDeleteOutboxService.createPendingDeferred("trace-001", "1-0"))
                    .thenReturn(101L);

            CompletionPersistenceResult persistenceResult =
                    trafficDeductCompletionPersistenceService.persistCompletion(payload, result, "1-0", 10L);

            assertTrue(persistenceResult.saved());
            assertEquals(101L, persistenceResult.outboxId());
            InOrder inOrder = inOrder(
                    trafficDeductDoneLogService,
                    trafficInFlightDedupeDeleteOutboxService
            );
            inOrder.verify(trafficDeductDoneLogService).saveIfAbsent(payload, result, "1-0", 10L);
            inOrder.verify(trafficInFlightDedupeDeleteOutboxService)
                    .createPendingDeferred("trace-001", "1-0");
        }

        @Test
        @DisplayName("done log 중복이어도 기존 정책대로 outbox PENDING을 생성한다")
        void createsDeferredOutboxWhenDoneLogIsDuplicate() {
            TrafficPayloadReqDto payload = payload("trace-duplicate");
            TrafficDeductResultResDto result = result();

            when(trafficDeductDoneLogService.saveIfAbsent(payload, result, "2-0", 20L))
                    .thenReturn(false);
            when(trafficInFlightDedupeDeleteOutboxService.createPendingDeferred("trace-duplicate", "2-0"))
                    .thenReturn(202L);

            CompletionPersistenceResult persistenceResult =
                    trafficDeductCompletionPersistenceService.persistCompletion(payload, result, "2-0", 20L);

            assertFalse(persistenceResult.saved());
            assertEquals(202L, persistenceResult.outboxId());
            InOrder inOrder = inOrder(
                    trafficDeductDoneLogService,
                    trafficInFlightDedupeDeleteOutboxService
            );
            inOrder.verify(trafficDeductDoneLogService).saveIfAbsent(payload, result, "2-0", 20L);
            inOrder.verify(trafficInFlightDedupeDeleteOutboxService)
                    .createPendingDeferred("trace-duplicate", "2-0");
        }

        @Test
        @DisplayName("done log 저장 실패 시 outbox PENDING을 생성하지 않는다")
        void doesNotCreateDeferredOutboxWhenDoneLogSaveFails() {
            TrafficPayloadReqDto payload = payload("trace-fail");
            TrafficDeductResultResDto result = result();
            RuntimeException exception = new RuntimeException("done log failed");

            when(trafficDeductDoneLogService.saveIfAbsent(payload, result, "3-0", 30L))
                    .thenThrow(exception);

            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> trafficDeductCompletionPersistenceService.persistCompletion(payload, result, "3-0", 30L)
            );

            assertEquals(exception, thrown);
            verify(trafficInFlightDedupeDeleteOutboxService, never())
                    .createPendingDeferred(any(), any());
        }

    }

    private TrafficPayloadReqDto payload(String traceId) {
        return TrafficPayloadReqDto.builder()
                .traceId(traceId)
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(1_700_000_000_000L)
                .build();
    }

    private TrafficDeductResultResDto result() {
        return TrafficDeductResultResDto.builder()
                .traceId("trace-001")
                .apiTotalData(100L)
                .deductedIndividualBytes(70L)
                .deductedSharedBytes(30L)
                .deductedQosBytes(0L)
                .apiRemainingData(0L)
                .finalStatus(TrafficFinalStatus.SUCCESS)
                .lastLuaStatus(TrafficLuaStatus.OK)
                .build();
    }
}
