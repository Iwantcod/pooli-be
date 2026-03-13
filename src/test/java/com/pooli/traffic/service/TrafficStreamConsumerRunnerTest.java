package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.traffic.domain.TrafficStreamFields;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;

@ExtendWith(MockitoExtension.class)
class TrafficStreamConsumerRunnerTest {

    @Mock
    private TrafficStreamInfraService trafficStreamInfraService;

    @Mock
    private TrafficDeductOrchestratorService trafficDeductOrchestratorService;

    @Mock
    private TrafficInFlightDedupeService trafficInFlightDedupeService;

    @Mock
    private TrafficDeductDonePersistenceService trafficDeductDonePersistenceService;

    @Mock
    private TrafficStreamReclaimService trafficStreamReclaimService;

    private TrafficStreamConsumerRunner trafficStreamConsumerRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AppStreamsProperties appStreamsProperties = new AppStreamsProperties();
        trafficStreamConsumerRunner = new TrafficStreamConsumerRunner(
                trafficStreamInfraService,
                appStreamsProperties,
                objectMapper,
                trafficDeductOrchestratorService,
                trafficInFlightDedupeService,
                trafficDeductDonePersistenceService,
                trafficStreamReclaimService
        );
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("handleRecord 테스트")
    class HandleRecordTest {

        @Test
        @DisplayName("payload traceId로 MDC를 초기화하고 처리 후 제거한다")
        void initializeAndClearMdcWithPayloadTraceId() {
            // given
            String payloadJson = "{\"traceId\":\"trace-001\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("1-0", payloadJson);
            AtomicReference<String> mdcTraceIdAtOrchestrator = new AtomicReference<>();
            TrafficDeductResultResDto orchestratorResult = TrafficDeductResultResDto.builder()
                    .traceId("trace-001")
                    .finalStatus(TrafficFinalStatus.SUCCESS)
                    .deductedTotalBytes(100L)
                    .apiRemainingData(0L)
                    .lastLuaStatus(TrafficLuaStatus.OK)
                    .build();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDonePersistenceService.existsByTraceId("trace-001")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-001")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenAnswer(invocation -> {
                mdcTraceIdAtOrchestrator.set(MDC.get("traceId"));
                return orchestratorResult;
            });
            when(trafficDeductDonePersistenceService.saveIfAbsent(any(), eq(orchestratorResult))).thenReturn(true);

            // when
            invokeHandleRecord(record);

            // then
            assertEquals("trace-001", mdcTraceIdAtOrchestrator.get());
            assertNull(MDC.get("traceId"));
            verify(trafficStreamInfraService).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-001");
        }

        @Test
        @DisplayName("traceId가 없는 payload면 DLQ 처리 후 MDC traceId를 남기지 않는다")
        void doesNotLeaveMdcWhenTraceIdIsBlank() {
            // given
            String payloadJson = "{\"traceId\":\"\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("2-0", payloadJson);
            MDC.put("traceId", "stale-trace-id");
            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);

            // when
            invokeHandleRecord(record);

            // then
            assertNull(MDC.get("traceId"));
            verify(trafficStreamInfraService).writeDlq(payloadJson, "traceId가 비어 있습니다.", "2-0");
            verify(trafficStreamInfraService).acknowledge(record.getId());
            verifyNoInteractions(trafficDeductOrchestratorService);
        }
    }

    private MapRecord<String, String, String> createRecord(String recordId, String payloadJson) {
        return MapRecord
                .<String, String, String>create(
                        "traffic:deduct:request",
                        Map.of(TrafficStreamFields.PAYLOAD, payloadJson)
                )
                .withId(RecordId.of(recordId));
    }

    private void invokeHandleRecord(MapRecord<String, String, String> record) {
        try {
            Method handleRecordMethod = TrafficStreamConsumerRunner.class
                    .getDeclaredMethod("handleRecord", MapRecord.class);
            handleRecordMethod.setAccessible(true);
            handleRecordMethod.invoke(trafficStreamConsumerRunner, record);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
