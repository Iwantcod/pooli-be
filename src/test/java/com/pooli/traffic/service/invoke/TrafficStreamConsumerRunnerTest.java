package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
public class TrafficStreamConsumerRunnerTest {

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
    private AppStreamsProperties appStreamsProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        appStreamsProperties = new AppStreamsProperties();
        appStreamsProperties.setReadCount(20);
        appStreamsProperties.setBlockMs(100L);
        appStreamsProperties.setWorkerThreadCount(2);
        appStreamsProperties.setWorkerQueueCapacity(2);
        appStreamsProperties.setWorkerRejectionPolicy("abort");
        appStreamsProperties.setReclaimPendingScanCount(10);
        appStreamsProperties.setReclaimIntervalMs(200L);
        appStreamsProperties.setReclaimMinIdleMs(15_000L);
        appStreamsProperties.setShutdownAwaitMs(300L);
        appStreamsProperties.setMaxRetry(5);
        TrafficPayloadValidationService trafficPayloadValidationService =
                new TrafficPayloadValidationService(validator);

        trafficStreamConsumerRunner = new TrafficStreamConsumerRunner(
                trafficStreamInfraService,
                appStreamsProperties,
                objectMapper,
                trafficPayloadValidationService,
                trafficDeductOrchestratorService,
                trafficInFlightDedupeService,
                trafficDeductDoneLogService,
                trafficStreamReclaimService
        );
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        ThreadPoolExecutor workerExecutor = getPrivateField("workerExecutor", ThreadPoolExecutor.class);
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        ExecutorService pollerExecutor = getPrivateField("pollerExecutor", ExecutorService.class);
        if (pollerExecutor != null) {
            pollerExecutor.shutdownNow();
        }
        ScheduledExecutorService reclaimExecutor = getPrivateField("reclaimExecutor", ScheduledExecutorService.class);
        if (reclaimExecutor != null) {
            reclaimExecutor.shutdownNow();
        }
        MDC.clear();
    }

    @Nested
    @DisplayName("handleRecord")
    class HandleRecordTest {

        @Test
        @DisplayName("uses payload traceId for MDC and clears it after success")
        void initializeAndClearMdcWithPayloadTraceId() {
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

            invokeHandleRecord(record);

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

            detachAppender(listAppender);
        }

        @Test
        @DisplayName("routes blank traceId payload to DLQ before dedupe")
        void routeBlankTraceIdPayloadToDlq() {
            String payloadJson = "{\"traceId\":\"\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("2-0", payloadJson);
            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            MDC.put("traceId", "stale-trace-id");

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);

            invokeHandleRecord(record);

            assertNull(MDC.get("traceId"));
            verify(trafficStreamInfraService).writeDlq(eq(payloadJson), reasonCaptor.capture(), eq("2-0"));
            verify(trafficStreamInfraService).acknowledge(record.getId());
            assertTrue(reasonCaptor.getValue().startsWith("payload validation failed:"));
            assertTrue(reasonCaptor.getValue().contains("traceId"));
            verifyNoInteractions(trafficDeductOrchestratorService);
            verifyNoInteractions(trafficInFlightDedupeService);
            verifyNoInteractions(trafficDeductDoneLogService);
        }

        @Test
        @DisplayName("routes non-trace required-field violations to DLQ before processing")
        void routeMissingRequiredFieldPayloadToDlq() {
            String payloadJson = "{\"traceId\":\"trace-invalid\",\"lineId\":0,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("2-1", payloadJson);
            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);

            invokeHandleRecord(record);

            verify(trafficStreamInfraService).writeDlq(eq(payloadJson), reasonCaptor.capture(), eq("2-1"));
            verify(trafficStreamInfraService).acknowledge(record.getId());
            assertTrue(reasonCaptor.getValue().contains("lineId"));
            verifyNoInteractions(trafficDeductOrchestratorService);
            verifyNoInteractions(trafficInFlightDedupeService);
            verifyNoInteractions(trafficDeductDoneLogService);
        }

        @Test
        @DisplayName("does not ack when DLQ write fails")
        void doesNotAckWhenDlqWriteFails() {
            String payloadJson = "";
            MapRecord<String, String, String> record = createRecord("2-2", payloadJson);

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficStreamInfraService.writeDlq(payloadJson, "payload 필드가 비어 있습니다.", "2-2"))
                    .thenThrow(new RuntimeException("dlq down"));

            RuntimeException thrown = null;
            try {
                invokeHandleRecord(record);
            } catch (RuntimeException e) {
                thrown = e;
            }

            assertTrue(thrown != null && thrown.getCause() != null);
            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verifyNoInteractions(trafficDeductOrchestratorService);
        }

        @Test
        @DisplayName("acks and releases when done log insert is duplicate")
        void acknowledgeWhenDoneLogIsDuplicate() {
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

            invokeHandleRecord(record);

            verify(trafficStreamInfraService).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-dup");

            String doneLog = findDoneLog(listAppender);
            assertTrue(doneLog.startsWith("traffic_stream_record_done_duplicate"));
            assertFalse(doneLog.contains("body="));

            detachAppender(listAppender);
        }

        @Test
        @DisplayName("saves blocked result then acks and releases")
        void acknowledgeWhenPolicyBlockedResultIsSaved() {
            String payloadJson = "{\"traceId\":\"trace-blocked\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("6-0", payloadJson);
            TrafficDeductResultResDto orchestratorResult = TrafficDeductResultResDto.builder()
                    .traceId("trace-blocked")
                    .apiTotalData(100L)
                    .finalStatus(TrafficFinalStatus.PARTIAL_SUCCESS)
                    .deductedTotalBytes(0L)
                    .apiRemainingData(100L)
                    .lastLuaStatus(TrafficLuaStatus.BLOCKED_IMMEDIATE)
                    .build();
            ListAppender<ILoggingEvent> listAppender = attachAppender();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-blocked")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-blocked")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenReturn(orchestratorResult);
            when(trafficDeductDoneLogService.saveIfAbsent(any(), eq(orchestratorResult), eq("6-0"))).thenReturn(true);

            invokeHandleRecord(record);

            ArgumentCaptor<TrafficDeductResultResDto> resultCaptor =
                    ArgumentCaptor.forClass(TrafficDeductResultResDto.class);
            verify(trafficDeductDoneLogService).saveIfAbsent(any(), resultCaptor.capture(), eq("6-0"));
            verify(trafficStreamInfraService).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-blocked");
            assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS, resultCaptor.getValue().getFinalStatus());
            assertEquals(TrafficLuaStatus.BLOCKED_IMMEDIATE, resultCaptor.getValue().getLastLuaStatus());

            String doneLog = findDoneLog(listAppender);
            assertTrue(doneLog.contains("final_status=PARTIAL_SUCCESS"));
            assertTrue(doneLog.contains("last_lua_status=BLOCKED_IMMEDIATE"));

            detachAppender(listAppender);
        }

        @Test
        @DisplayName("does not ack but releases claim when done log save fails")
        void doesNotAckWhenDoneLogSaveFails() {
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

            invokeHandleRecord(record);

            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-fail");
        }

        @Test
        @DisplayName("does not ack but releases claim when orchestration fails")
        void doesNotAckWhenOrchestrationFails() {
            String payloadJson = "{\"traceId\":\"trace-orchestrate-fail\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("4-1", payloadJson);

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-orchestrate-fail")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-orchestrate-fail")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any()))
                    .thenThrow(new RuntimeException("redis timeout"));

            invokeHandleRecord(record);

            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verify(trafficInFlightDedupeService).release("trace-orchestrate-fail");
        }

        @Test
        @DisplayName("acks when claim fails but done log already exists")
        void acknowledgeWhenClaimFailedButAlreadyLogged() {
            String payloadJson = "{\"traceId\":\"trace-claimed\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("5-0", payloadJson);

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-claimed"))
                    .thenReturn(false)
                    .thenReturn(true);
            when(trafficInFlightDedupeService.tryClaim("trace-claimed")).thenReturn(false);

            invokeHandleRecord(record);

            verify(trafficStreamInfraService).acknowledge(record.getId());
            verifyNoInteractions(trafficDeductOrchestratorService);
        }

        @Test
        @DisplayName("leaves record pending when claim fails and no done log exists")
        void leaveRecordPendingWhenClaimFailedAndNotLogged() {
            String payloadJson = "{\"traceId\":\"trace-pending\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("5-1", payloadJson);

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-pending"))
                    .thenReturn(false)
                    .thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-pending")).thenReturn(false);

            invokeHandleRecord(record);

            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verifyNoInteractions(trafficDeductOrchestratorService);
        }

        @Test
        @DisplayName("persists done log before ack and releases after ack")
        void acknowledgeOnlyAfterDoneLogSaved() {
            String payloadJson = "{\"traceId\":\"trace-order\",\"lineId\":11,\"familyId\":22,\"appId\":33,\"apiTotalData\":100,\"enqueuedAt\":1700000000000}";
            MapRecord<String, String, String> record = createRecord("7-0", payloadJson);
            TrafficDeductResultResDto orchestratorResult = TrafficDeductResultResDto.builder()
                    .traceId("trace-order")
                    .apiTotalData(100L)
                    .finalStatus(TrafficFinalStatus.SUCCESS)
                    .deductedTotalBytes(100L)
                    .apiRemainingData(0L)
                    .lastLuaStatus(TrafficLuaStatus.OK)
                    .build();

            when(trafficStreamInfraService.extractPayload(record)).thenReturn(payloadJson);
            when(trafficDeductDoneLogService.existsByTraceId("trace-order")).thenReturn(false);
            when(trafficInFlightDedupeService.tryClaim("trace-order")).thenReturn(true);
            when(trafficDeductOrchestratorService.orchestrate(any())).thenReturn(orchestratorResult);
            when(trafficDeductDoneLogService.saveIfAbsent(any(), eq(orchestratorResult), eq("7-0"))).thenReturn(true);

            invokeHandleRecord(record);

            InOrder inOrder = inOrder(
                    trafficDeductDoneLogService,
                    trafficStreamInfraService,
                    trafficInFlightDedupeService
            );
            inOrder.verify(trafficDeductDoneLogService).saveIfAbsent(any(), eq(orchestratorResult), eq("7-0"));
            inOrder.verify(trafficStreamInfraService).acknowledge(record.getId());
            inOrder.verify(trafficInFlightDedupeService).release("trace-order");
        }
    }

    @Nested
    @DisplayName("pressure boundary")
    class PressureBoundaryTest {

        @Test
        @DisplayName("leaves record pending when dispatch is rejected by full worker queue")
        void leaveRecordPendingWhenDispatchRejected() throws Exception {
            MapRecord<String, String, String> record = createRecord("9-0", "{\"traceId\":\"trace-rejected\"}");
            CountDownLatch activeTaskStarted = new CountDownLatch(1);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            ListAppender<ILoggingEvent> listAppender = attachAppender();

            executor.execute(() -> awaitLatch(activeTaskStarted, releaseTasks));
            assertTrue(activeTaskStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            setPrivateField("workerExecutor", executor);
            getPrivateField("running", java.util.concurrent.atomic.AtomicBoolean.class).set(true);

            invokeDispatchRecord(record);

            verify(trafficStreamInfraService, never()).acknowledge(record.getId());
            verifyNoInteractions(trafficDeductOrchestratorService);
            assertTrue(
                    listAppender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message -> message.startsWith("traffic_stream_record_dispatch_rejected"))
            );

            releaseTasks.countDown();
            detachAppender(listAppender);
        }

        @Test
        @DisplayName("does not poll new records while worker capacity is exhausted")
        void doesNotPollWhenWorkerCapacityIsExhausted() throws Exception {
            CountDownLatch activeTaskStarted = new CountDownLatch(1);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            ListAppender<ILoggingEvent> listAppender = attachAppender();

            executor.execute(() -> awaitLatch(activeTaskStarted, releaseTasks));
            assertTrue(activeTaskStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            setPrivateField("workerExecutor", executor);

            invokeConsumeNextBatch();

            verify(trafficStreamInfraService, never()).readBlocking(anyInt());
            assertTrue(
                    listAppender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message -> message.startsWith("traffic_stream_worker_pressure_on"))
            );

            releaseTasks.countDown();
            detachAppender(listAppender);
        }

        @Test
        @DisplayName("polls only up to remaining worker capacity")
        void pollOnlyRemainingWorkerCapacity() throws Exception {
            CountDownLatch activeTaskStarted = new CountDownLatch(1);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(3),
                    new ThreadPoolExecutor.AbortPolicy()
            );

            appStreamsProperties.setReadCount(20);
            executor.execute(() -> awaitLatch(activeTaskStarted, releaseTasks));
            assertTrue(activeTaskStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            setPrivateField("workerExecutor", executor);
            when(trafficStreamInfraService.readBlocking(2)).thenReturn(List.of());

            invokeConsumeNextBatch();

            verify(trafficStreamInfraService).readBlocking(2);

            releaseTasks.countDown();
        }

        @Test
        @DisplayName("skips dispatch while stopping so reclaim does not enqueue new work")
        void skipDispatchWhileStopping() {
            MapRecord<String, String, String> record = createRecord("10-0", "{\"traceId\":\"trace-stop\"}");
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    new ThreadPoolExecutor.AbortPolicy()
            );

            setPrivateField("workerExecutor", executor);
            getPrivateField("running", AtomicBoolean.class).set(false);

            invokeDispatchRecord(record);

            assertEquals(0, executor.getQueue().size());
            verifyNoInteractions(trafficStreamInfraService);
        }

        @Test
        @DisplayName("reclaim only claims up to remaining worker capacity")
        void reclaimOnlyClaimsUpToRemainingWorkerCapacity() throws Exception {
            CountDownLatch activeTaskStarted = new CountDownLatch(1);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(3),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            MapRecord<String, String, String> reclaimedRecord = createRecord("11-0", "{\"traceId\":\"trace-reclaim\"}");

            executor.execute(() -> awaitLatch(activeTaskStarted, releaseTasks));
            assertTrue(activeTaskStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            executor.execute(() -> awaitLatch(null, releaseTasks));
            setPrivateField("workerExecutor", executor);
            getPrivateField("running", AtomicBoolean.class).set(true);
            when(trafficStreamReclaimService.reclaimAndRouteExceededRetries(2)).thenReturn(List.of(reclaimedRecord));

            invokeRunReclaimCycle();

            verify(trafficStreamReclaimService).reclaimAndRouteExceededRetries(2);
            releaseTasks.countDown();
        }
    }

    @Nested
    @DisplayName("shutdown boundary")
    class ShutdownBoundaryTest {

        @Test
        @DisplayName("gracefully waits for worker completion before forcing shutdown")
        void gracefullyWaitForWorkerCompletion() throws Exception {
            CountDownLatch taskCompleted = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean(false);
            ThreadPoolExecutor worker = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            ExecutorService poller = Executors.newSingleThreadExecutor();
            ScheduledExecutorService reclaim = Executors.newSingleThreadScheduledExecutor();

            worker.execute(() -> {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    taskCompleted.countDown();
                }
            });

            setPrivateField("pollerExecutor", poller);
            setPrivateField("workerExecutor", worker);
            setPrivateField("reclaimExecutor", reclaim);
            getPrivateField("running", AtomicBoolean.class).set(true);

            trafficStreamConsumerRunner.stop();

            assertTrue(taskCompleted.await(1, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
            assertTrue(worker.isTerminated());
            assertFalse(trafficStreamConsumerRunner.isRunning());
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

    private void invokeDispatchRecord(MapRecord<String, String, String> record) {
        try {
            Method dispatchRecordMethod = TrafficStreamConsumerRunner.class
                    .getDeclaredMethod("dispatchRecord", MapRecord.class);
            dispatchRecordMethod.setAccessible(true);
            dispatchRecordMethod.invoke(trafficStreamConsumerRunner, record);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeConsumeNextBatch() {
        try {
            Method consumeNextBatchMethod = TrafficStreamConsumerRunner.class
                    .getDeclaredMethod("consumeNextBatch");
            consumeNextBatchMethod.setAccessible(true);
            consumeNextBatchMethod.invoke(trafficStreamConsumerRunner);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeRunReclaimCycle() {
        try {
            Method runReclaimCycleMethod = TrafficStreamConsumerRunner.class
                    .getDeclaredMethod("runReclaimCycle");
            runReclaimCycleMethod.setAccessible(true);
            runReclaimCycleMethod.invoke(trafficStreamConsumerRunner);
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

    private void detachAppender(ListAppender<ILoggingEvent> listAppender) {
        Logger logger = (Logger) LoggerFactory.getLogger(TrafficStreamConsumerRunner.class);
        logger.detachAppender(listAppender);
    }

    private String findDoneLog(ListAppender<ILoggingEvent> listAppender) {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("traffic_stream_record_done"))
                .findFirst()
                .orElse("");
    }

    private void setPrivateField(String fieldName, Object value) {
        try {
            Field field = TrafficStreamConsumerRunner.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(trafficStreamConsumerRunner, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T getPrivateField(String fieldName, Class<T> fieldType) {
        try {
            Field field = TrafficStreamConsumerRunner.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(trafficStreamConsumerRunner);
            if (value == null) {
                return null;
            }
            return fieldType.cast(value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitLatch(CountDownLatch startedLatch, CountDownLatch releaseLatch) {
        if (startedLatch != null) {
            startedLatch.countDown();
        }

        try {
            releaseLatch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
