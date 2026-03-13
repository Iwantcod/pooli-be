package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
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
    private TrafficDeductDoneLogService trafficDeductDoneLogService;

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
                trafficDeductDoneLogService,
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
                    .apiTotalData(100L)
                    .finalStatus(TrafficFinalStatus.SUCCESS)
                    .deductedTotalBytes(100L)
                    .apiRemainingData(0L)
                    .lastLuaStatus(TrafficLuaStatus.OK)
                    .build();
            ListAppender<ILoggingEvent> listAppender = attachAppender();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-001")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-001")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenAnswer(invocation -> {
                mdcTraceIdAtOrchestrator.set(MDC.get("traceId"));
                return orchestratorResult;
            });
            when(trafficDeductDoneLogService.saveIfAbsent(any(), eq(orchestratorResult), eq("1-0"))).thenReturn(true);

            // when
            invokeHandleRecord(record);

            // then
            assertEquals("trace-001", mdcTraceIdAtOrchestrator.get());
            assertNull(MDC.get("traceId"));
            verify(trafficStreamInfraService).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-001");

            String doneLog = findDoneLog(listAppender);
            assertTrue(doneLog.contains("trace_id=trace-001"));
            assertTrue(doneLog.contains("record_id=1-0"));
            assertTrue(doneLog.startsWith("traffic_stream_record_done"));
            assertTrue(doneLog.contains("logged_at="));
            assertFalse(doneLog.contains("body="));

            Logger logger = (Logger) LoggerFactory.getLogger(TrafficStreamConsumerRunner.class);
            logger.detachAppender(listAppender);
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

        @Test
        @DisplayName("완료 로그 저장 결과가 중복(false)이어도 ACK 후 release 한다")
        void acknowledgeWhenDoneLogIsDuplicate() {
            // given
            String payloadJson = "{\"traceId\":\"trace-dup\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("3-0", payloadJson);
            TrafficDeductResultResDto orchestratorResult = TrafficDeductResultResDto.builder()
                    .traceId("trace-dup")
                    .apiTotalData(100L)
                    .finalStatus(TrafficFinalStatus.PARTIAL_SUCCESS)
                    .deductedTotalBytes(60L)
                    .apiRemainingData(40L)
                    .lastLuaStatus(TrafficLuaStatus.NO_BALANCE)
                    .build();
            ListAppender<ILoggingEvent> listAppender = attachAppender();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-dup")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-dup")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenReturn(orchestratorResult);
            when(trafficDeductDoneLogService.saveIfAbsent(any(), eq(orchestratorResult), eq("3-0"))).thenReturn(false);

            // when
            invokeHandleRecord(record);

            // then
            verify(trafficStreamInfraService).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-dup");

            String doneLog = findDoneLog(listAppender);
            assertTrue(doneLog.startsWith("traffic_stream_record_done_duplicate"));
            assertFalse(doneLog.contains("body="));

            Logger logger = (Logger) LoggerFactory.getLogger(TrafficStreamConsumerRunner.class);
            logger.detachAppender(listAppender);
        }

        @Test
        @DisplayName("완료 로그 저장 실패 예외가 발생하면 ACK/release 하지 않는다")
        void doesNotAckWhenDoneLogSaveFails() {
            // given
            String payloadJson = "{\"traceId\":\"trace-fail\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("4-0", payloadJson);
            TrafficDeductResultResDto orchestratorResult = TrafficDeductResultResDto.builder()
                    .traceId("trace-fail")
                    .apiTotalData(100L)
                    .finalStatus(TrafficFinalStatus.SUCCESS)
                    .deductedTotalBytes(100L)
                    .apiRemainingData(0L)
                    .lastLuaStatus(TrafficLuaStatus.OK)
                    .build();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-fail")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-fail")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenReturn(orchestratorResult);
            when(trafficDeductDoneLogService.saveIfAbsent(any(), eq(orchestratorResult), eq("4-0")))
                    .thenThrow(new RuntimeException("mongo down"));

            // when
            invokeHandleRecord(record);

            // then
            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verify(trafficInFlightDedupeService, never()).release("trace-fail");
        }

        @Test
        @DisplayName("claim 실패 후 이미 완료 로그가 있으면 ACK로 정리한다")
        void acknowledgeWhenClaimFailedButAlreadyLogged() {
            // given
            String payloadJson = "{\"traceId\":\"trace-claimed\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("5-0", payloadJson);
            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-claimed"))
                    .thenReturn(false)
                    .thenReturn(true);
            when(trafficInFlightDedupeService.tryClaim("trace-claimed")).thenReturn(false);

            // when
            invokeHandleRecord(record);

            // then
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

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TrafficStreamConsumerRunner.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        return listAppender;
    }

    private String findDoneLog(ListAppender<ILoggingEvent> listAppender) {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("traffic_stream_record_done"))
                .findFirst()
                .orElse("");
    }
}
