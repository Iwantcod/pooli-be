package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.pooli.monitoring.metrics.TrafficGeneratorMetrics;
import com.pooli.monitoring.metrics.TrafficRecordStageMetricsPort;
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntry;
import com.pooli.traffic.domain.TrafficInFlightIdempotencyEntryResult;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.service.outbox.TrafficInFlightDedupeDeleteOutboxService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.config.AppStreamsProperties.WorkerRejectionPolicy;
import com.pooli.common.dto.Violation;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Streams BLOCK 소비 루프를 실행하는 러너입니다.
 * poller 스레드는 레코드를 읽고, worker 풀은 레코드 처리 로직을 병렬 수행합니다.
 * worker는 payload 역직렬화 후 이벤트 단일 사이클 오케스트레이터를 호출합니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamConsumerRunner implements SmartLifecycle {

    private static final String TRACE_ID_MDC_KEY = "traceId";
    private static final String STAGE_PARSE_VALIDATE = "parse_validate";
    private static final String STAGE_DEDUPE = "dedupe";
    private static final String STAGE_ORCHESTRATE = "orchestrate";
    private static final String STAGE_DONE_LOG_SAVE = "done_log_save";
    private static final String STAGE_ACK = "ack";
    private static final String STAGE_TOTAL = "total";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_DLQ = "dlq";
    private static final String RESULT_DEDUPED = "deduped";
    private static final int RECLAIM_RETRY_EXCEEDED_THRESHOLD = 6;

    // Streams read/ack/DLQ 인프라 유틸
    private final TrafficStreamInfraService trafficStreamInfraService;
    // app.streams.* 설정값
    private final AppStreamsProperties appStreamsProperties;
    // payload JSON 역직렬화 도구
    private final ObjectMapper objectMapper;
    private final TrafficPayloadValidationService trafficPayloadValidationService;
    private final TrafficDeductOrchestratorService trafficDeductOrchestratorService;
    // in-flight dedupe 선점 서비스(traceId 기준)
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;
    // 완료 로그 서비스(traceId UNIQUE idempotency)
    private final TrafficDeductDoneLogService trafficDeductDoneLogService;
    // pending reclaim/retry/DLQ 분기 서비스
    private final TrafficStreamReclaimService trafficStreamReclaimService;
    // in-flight dedupe key 삭제 요청 outbox 적재 서비스
    private final TrafficInFlightDedupeDeleteOutboxService trafficInFlightDedupeDeleteOutboxService;
    // Redis 인프라 예외(timeout/connection) 분류기
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final TrafficGeneratorMetrics trafficGeneratorMetrics;
    private final TrafficRecordStageMetricsPort trafficRecordStageMetricsPort;

    // 전역적인 소비 루프 동작 여부 플래그(start/stop 간 공유)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean workerPressureActive = new AtomicBoolean(false);
    private int workerPressureRetryAttempt = 0;

    @Value("${app.traffic.deduct.redis-retry.backoff-ms:50}")
    private long retryBackoffMs = 50L;

    private ExecutorService pollerExecutor;
    private ThreadPoolExecutor workerExecutor;
    private ScheduledExecutorService reclaimExecutor;

    /**
     * 애플리케이션 시작 시점에 필요한 초기화 작업을 수행합니다.
     */
    @Override
    public void start() {
        // 오케스트레이터가 아직 연결되지 않은 환경에서 소비기를 켜면
        // 메시지가 불완전 처리될 수 있으므로, 설정값으로 기동 여부를 먼저 확인한다.
        if (!appStreamsProperties.isConsumerEnabled()) {
            log.info("traffic_stream_consumer_disabled enabled=false");
            return;
        }

        // 읽기 전 Consumer Group 존재를 보장한다.
        // 이미 생성된 그룹이면 infra 서비스에서 안전하게 무시 처리한다.
        trafficStreamInfraService.ensureConsumerGroup();

        int workerThreadCount = resolveWorkerThreadCount();
        int workerQueueCapacity = appStreamsProperties.requireWorkerQueueCapacity();
        WorkerRejectionPolicy rejectionPolicy = appStreamsProperties.requireWorkerRejectionPolicy();

        // poller는 BLOCK read 전용이므로 단일 스레드면 충분하다.
        pollerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "traffic-stream-poller");
            // traffic 프로파일(web-application-type=none)에서는
            // non-daemon 워커가 최소 1개는 있어야 JVM이 즉시 종료되지 않는다.
            thread.setDaemon(false);
            return thread;
        });

        workerExecutor = new ThreadPoolExecutor(
                workerThreadCount,
                workerThreadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workerQueueCapacity),
                r -> {
                    Thread thread = new Thread(r, "traffic-stream-worker");
                    thread.setDaemon(false);
                    return thread;
                },
                buildRejectedExecutionHandler(rejectionPolicy)
        );
        publishWorkerRuntimeMetrics();

        // running 플래그를 먼저 켠 뒤 루프를 제출해
        // 제출 직후 루프가 즉시 종료되는 경쟁 상태를 피한다.
        running.set(true);
        pollerExecutor.submit(this::consumeLoop);
        startReclaimLoop();
        log.info(
                "traffic_stream_consumer_started group={} consumer={} workerThreads={} workerQueueCapacity={} rejectionPolicy={}",
                appStreamsProperties.getGroupTraffic(),
                appStreamsProperties.getConsumerName(),
                workerThreadCount,
                workerQueueCapacity,
                rejectionPolicy
        );
    }

    /**
     * 애플리케이션 종료 시점에 실행 중인 리소스를 안전하게 정리합니다.
     */
    @Override
    public void stop() {
        // 루프가 다음 사이클에서 종료되도록 먼저 상태를 내린다.
        running.set(false);

        shutdownExecutor("traffic-stream-reclaim", reclaimExecutor, 0L);
        shutdownExecutor("traffic-stream-poller", pollerExecutor, 0L);
        shutdownExecutor("traffic-stream-worker", workerExecutor, appStreamsProperties.requireShutdownAwaitMs());
        trafficGeneratorMetrics.updateWorkerIdleThreads(0);
        trafficGeneratorMetrics.updateWorkerQueueSize(0);

        log.info("traffic_stream_consumer_stopped");
    }

    /**
     * 콜백 기반 종료 시그니처를 지원하기 위해 stop 이후 콜백을 즉시 실행합니다.
     *
     * @param callback stop 이후 실행할 콜백
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 소비 루프 실행 상태를 반환합니다.
     *
     * @return 실행 중이면 {@code true}
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * SmartLifecycle 자동 시작 여부를 반환합니다.
     *
     * @return 항상 {@code true}
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 종료 순서를 늦추기 위한 phase 값을 반환합니다.
     *
     * @return 최대 phase 값
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * poller 루프를 실행해 배치를 계속 소비합니다.
     */
    private void consumeLoop() {
        // SmartLifecycle stop() 호출 전까지 BLOCK read -> 레코드 처리 과정을 반복한다.
        while (running.get()) {
            try {
                consumeNextBatch();
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.error("traffic_stream_consume_loop_failed", e);
            }
        }
    }

    /**
     * worker 처리 여유를 반영해 다음 배치를 읽고 레코드를 worker로 분배합니다.
     */
    private void consumeNextBatch() {
        publishWorkerRuntimeMetrics();
        int nextReadCount = resolveNextReadCount();
        if (nextReadCount <= 0) {
            signalWorkerPressure();
            pauseForWorkerCapacity();
            return;
        }

        workerPressureRetryAttempt = 0;
        clearWorkerPressureSignal();

        List<MapRecord<String, String, String>> records = trafficStreamInfraService.readBlocking(nextReadCount);
        for (MapRecord<String, String, String> record : records) {
            dispatchRecord(record, TrafficStreamMessageSource.NEW);
        }
    }

    /**
     * 단일 레코드를 worker 풀에 제출합니다.
     *
     * @param record 제출할 스트림 레코드
     */
    private void dispatchRecord(MapRecord<String, String, String> record) {
        dispatchRecord(record, TrafficStreamMessageSource.NEW);
    }

    /**
     * 단일 레코드를 worker 풀에 제출합니다.
     *
     * @param record 제출할 스트림 레코드
     * @param messageSource 메시지 유입 출처
     */
    private void dispatchRecord(
            MapRecord<String, String, String> record,
            TrafficStreamMessageSource messageSource
    ) {
        if (!running.get()) {
            log.info("traffic_stream_record_dispatch_skipped recordId={} reason=stopping", record.getId().getValue());
            return;
        }

        if (workerExecutor == null) {
            log.warn("traffic_stream_worker_not_ready recordId={}", record.getId().getValue());
            return;
        }

        try {
            workerExecutor.execute(() -> handleRecord(record, messageSource));
        } catch (RejectedExecutionException e) {
            signalWorkerPressure();
            log.warn(
                    "traffic_stream_record_dispatch_rejected recordId={} running={} activeWorkers={} queueSize={} queueCapacity={} rejectionPolicy={}",
                    record.getId().getValue(),
                    running.get(),
                    workerExecutor.getActiveCount(),
                    workerExecutor.getQueue().size(),
                    workerExecutor.getQueue().remainingCapacity() + workerExecutor.getQueue().size(),
                    appStreamsProperties.requireWorkerRejectionPolicy()
            );
        } finally {
            publishWorkerRuntimeMetrics();
        }
    }

    /**
     * reclaim 스케줄러를 시작합니다.
     */
    private void startReclaimLoop() {
        long reclaimIntervalMs = appStreamsProperties.requireReclaimIntervalMs();

        // reclaim은 주기적으로 pending 목록을 점검하는 보조 작업이므로 단일 스레드면 충분하다.
        reclaimExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "traffic-stream-reclaim");
            thread.setDaemon(false);
            return thread;
        });

        reclaimExecutor.scheduleWithFixedDelay(
                this::runReclaimCycle,
                reclaimIntervalMs,
                reclaimIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * reclaim 주기 1회를 수행해 pending 메시지를 재분배합니다.
     */
    private void runReclaimCycle() {
        if (!running.get()) {
            return;
        }

        try {
            int reclaimDispatchLimit = resolveNextReadCount();
            if (reclaimDispatchLimit <= 0) {
                return;
            }

            List<MapRecord<String, String, String>> reclaimedRecords =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries(reclaimDispatchLimit);

            for (MapRecord<String, String, String> reclaimedRecord : reclaimedRecords) {
                dispatchRecord(reclaimedRecord, TrafficStreamMessageSource.RECLAIM);
            }
        } catch (Exception e) {
            // reclaim 실패가 메인 소비 루프를 멈추게 해서는 안 되므로 로그 후 다음 주기를 기다린다.
            log.error("traffic_stream_reclaim_cycle_failed", e);
        }
    }

    /**
     * 입력 상태를 해석해 분기별 처리 로직을 수행합니다.
     *
     * @param record 처리할 스트림 레코드
     * @param messageSource 메시지 유입 출처
     */
    private void handleRecord(MapRecord<String, String, String> record, TrafficStreamMessageSource messageSource) {
        long consumeStartTimeMs = System.currentTimeMillis();
        long handleStartNs = System.nanoTime();
        String resultTag = RESULT_FAILED;
        // worker 스레드는 재사용되므로 이전 레코드의 MDC가 남지 않게 먼저 비운다.
        MDC.remove(TRACE_ID_MDC_KEY);

        // DLQ/로그 추적을 위해 레코드 ID를 초기에 추출해 둔다.
        String recordId = record.getId().getValue();

        // 명세(field=payload)에 맞춰 payload 문자열을 가져온다.
        String payloadJson = trafficStreamInfraService.extractPayload(record);

        try {
            if (payloadJson == null || payloadJson.isBlank()) {
                // payload 자체가 없으면 이후 처리 불가능하므로 DLQ로 우회 후 ACK한다.
                trafficStreamInfraService.writeDlq(payloadJson, "payload 필드가 비어 있습니다.", recordId);
                acknowledgeWithMetrics(record.getId());
                resultTag = RESULT_DLQ;
                return;
            }

            TrafficPayloadReqDto payload;
            long parseValidateStartNs = System.nanoTime();
            // JSON payload를 DTO로 역직렬화해 이후 오케스트레이터가 바로 사용할 수 있게 한다.
            try {
                payload = objectMapper.readValue(payloadJson, TrafficPayloadReqDto.class);
                List<Violation> violations = trafficPayloadValidationService.validate(payload);
                if (!violations.isEmpty()) {
                    trafficStreamInfraService.writeDlq(payloadJson, buildValidationFailureReason(violations), recordId);
                    acknowledgeWithMetrics(record.getId());
                    resultTag = RESULT_DLQ;
                    return;
                }
            } catch (JsonProcessingException e) {
                long latency = System.currentTimeMillis() - consumeStartTimeMs;
                log.error("MQ message schema invalid recordId={} latency={}", recordId, e, latency);
                // 스키마 불일치/JSON 파손은 재처리해도 복구가 어려우므로 DLQ로 분기한다.
                trafficStreamInfraService.writeDlq(payloadJson, "payload 역직렬화 실패", recordId);
                acknowledgeWithMetrics(record.getId());
                resultTag = RESULT_DLQ;
                return;
            } finally {
                trafficRecordStageMetricsPort.recordStageLatency(STAGE_PARSE_VALIDATE, elapsedSinceNs(parseValidateStartNs));
            }

            String traceId = payload.getTraceId();
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            // createOrGet(traceId)가 성공한 뒤 DLQ로 종결되는 경우에만 cleanup outbox를 적재한다.
            // dedupe 생성/확보 전 실패에는 삭제 대상 key가 있다고 단정할 수 없으므로 false로 둔다.
            boolean dedupeCleanupRequired = false;
            try {
                long originalApiTotalData = normalizeNonNegative(payload.getApiTotalData());
                long processedIndividualDataBefore = 0L;
                long processedSharedDataBefore = 0L;
                long processedTotalDataBefore = 0L;
                long remainingDataToProcess = originalApiTotalData;

                long dedupeStartNs = System.nanoTime();
                try {
                    // done log precheck는 reclaim 경로에서만 수행한다.
                    if (messageSource == TrafficStreamMessageSource.RECLAIM
                            && trafficDeductDoneLogService.existsByTraceId(traceId)) {
                        trafficInFlightDedupeDeleteOutboxService.createPending(traceId, recordId);
                        acknowledgeWithMetrics(record.getId());
                        log.info("traffic_stream_record_already_done recordId={}", recordId);
                        resultTag = RESULT_DEDUPED;
                        return;
                    }

                    TrafficInFlightIdempotencyEntryResult entryResult =
                            trafficInFlightDedupeService.createOrGet(traceId);
                    dedupeCleanupRequired = true;
                    TrafficInFlightIdempotencyEntry entry = entryResult.entry();
                    processedIndividualDataBefore = normalizeNonNegative(entry == null ? null : entry.processedIndividualData());
                    processedSharedDataBefore = normalizeNonNegative(entry == null ? null : entry.processedSharedData());
                    processedTotalDataBefore = processedIndividualDataBefore + processedSharedDataBefore;
                    remainingDataToProcess = clampRemaining(originalApiTotalData - processedTotalDataBefore);

                    if (messageSource == TrafficStreamMessageSource.RECLAIM) {
                        // reclaim으로 워커가 실제 처리를 시작한 시점에 retryCount를 증가시킨다.
                        int reclaimRetryCountAfterIncrement = trafficInFlightDedupeService.incrementRetryOnReclaim(traceId);
                        // 증가 후 값이 상한(6) 이상이면 오케스트레이션을 건너뛰고 종결 경로로 즉시 전환한다.
                        if (reclaimRetryCountAfterIncrement >= RECLAIM_RETRY_EXCEEDED_THRESHOLD) {
                            handleReclaimRetryExceeded(
                                    payload,
                                    payloadJson,
                                    recordId,
                                    record.getId(),
                                    reclaimRetryCountAfterIncrement
                            );
                            // return 직전 결과 태그를 갱신해야 finally 블록에서 DLQ 결과 메트릭이 올바르게 집계된다.
                            resultTag = RESULT_DLQ;
                            return;
                        }
                    }

                    if (!entryResult.created()) {
                        log.info(
                                "traffic_stream_record_resume traceId={} recordId={} processedIndividualData={} processedSharedData={} processedTotalData={} remaining={}",
                                traceId,
                                recordId,
                                processedIndividualDataBefore,
                                processedSharedDataBefore,
                                processedTotalDataBefore,
                                remainingDataToProcess
                        );
                    }
                } finally {
                    trafficRecordStageMetricsPort.recordStageLatency(STAGE_DEDUPE, elapsedSinceNs(dedupeStartNs));
                }

                // 이벤트 단위 오케스트레이터를 실행해 개인풀/공유풀 차감 결과를 계산한다.
                TrafficDeductResultResDto executionResult;
                long orchestrateStartNs = System.nanoTime();
                try {
                    if (remainingDataToProcess <= 0L) {
                        executionResult = buildNoopExecutionResult(traceId);
                    } else {
                        executionResult = trafficDeductOrchestratorService.orchestrate(payload);
                    }
                } finally {
                    trafficRecordStageMetricsPort.recordStageLatency(STAGE_ORCHESTRATE, elapsedSinceNs(orchestrateStartNs));
                }
                TrafficDeductResultResDto cumulativeResult = buildCumulativeResult(
                        payload,
                        executionResult,
                        processedIndividualDataBefore,
                        processedSharedDataBefore
                );

                // 완료 로그 저장 시점에 함께 남길 지연 시간(ms) 값을 계산한다.
                // latency는 레코드 처리 시작 시점부터 done-log 저장 직전까지의 ms를 사용한다.
                long latency = Math.max(0L, System.currentTimeMillis() - consumeStartTimeMs);

                boolean saved;
                long mysqlSaveStartNs = System.nanoTime();
                try {
                    saved = trafficDeductDoneLogService.saveIfAbsent(payload, cumulativeResult, recordId, latency);
                } catch (Exception saveException) {
                    throw new DoneLogPersistenceException("traffic_done_log_save_failed", saveException);
                } finally {
                    trafficRecordStageMetricsPort.recordStageLatency(STAGE_DONE_LOG_SAVE, elapsedSinceNs(mysqlSaveStartNs));
                }

                if (!saved && messageSource == TrafficStreamMessageSource.NEW) {
                    log.warn(
                            "traffic_stream_duplicate_deduction_absorbed trace_id={} record_id={} source={} "
                                    + "api_total_data={} deducted_total_bytes={} api_remaining_data={} final_status={} last_lua_status={}",
                            payload.getTraceId(),
                            recordId,
                            messageSource,
                            cumulativeResult.getApiTotalData(),
                            cumulativeResult.getDeductedTotalBytes(),
                            cumulativeResult.getApiRemainingData(),
                            cumulativeResult.getFinalStatus(),
                            cumulativeResult.getLastLuaStatus()
                    );
                }

                trafficInFlightDedupeDeleteOutboxService.createPending(traceId, recordId);
                acknowledgeWithMetrics(record.getId());

                trafficGeneratorMetrics.incrementProcessed();
                resultTag = RESULT_SUCCESS;

                LocalDateTime loggedAt = LocalDateTime.now();
                String logEventName = saved ? "traffic_stream_record_done" : "traffic_stream_record_done_duplicate";

                log.info(
                        logEventName + " "
                                + "trace_id={} record_id={} line_id={} family_id={} app_id={} "
                                + "api_total_data={} deducted_total_bytes={} api_remaining_data={} "
                                + "final_status={} last_lua_status={} created_at={} finished_at={} logged_at={} latency={}",
                        payload.getTraceId(),
                        recordId,
                        payload.getLineId(),
                        payload.getFamilyId(),
                        payload.getAppId(),
                        cumulativeResult.getApiTotalData(),
                        cumulativeResult.getDeductedTotalBytes(),
                        cumulativeResult.getApiRemainingData(),
                        cumulativeResult.getFinalStatus(),
                        cumulativeResult.getLastLuaStatus(),
                        cumulativeResult.getCreatedAt(),
                        cumulativeResult.getFinishedAt(),
                        loggedAt,
                        latency
                );
            } catch (DoneLogPersistenceException e) {
                long latency = System.currentTimeMillis() - consumeStartTimeMs;
                // done log 저장 실패 시 ACK하면 재처리 기회를 잃으므로 pending을 유지한다.
                log.error(
                        "traffic_stream_record_done_log_save_failed recordId={} traceId={} latency={}",
                        recordId,
                        traceId,
                        latency,
                        e.getCause() == null ? e : e.getCause()
                );
            } catch (CumulativeInvariantViolationException e) {
                long latency = System.currentTimeMillis() - consumeStartTimeMs;
                log.error(
                        "traffic_stream_record_cumulative_invariant_violation recordId={} traceId={} latency={} reason={}",
                        recordId,
                        traceId,
                        latency,
                        e.getMessage(),
                        e
                );
                String dlqReason = "누적 차감량 불변식 위반: " + e.getMessage();
                handleTraceAwareNonRetryableFailure(
                        payload,
                        payloadJson,
                        recordId,
                        record.getId(),
                        e,
                        dlqReason,
                        dedupeCleanupRequired
                );
                resultTag = RESULT_DLQ;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - consumeStartTimeMs;
                if (trafficRedisFailureClassifier.isRetryableInfrastructureFailure(e)) {
                    // 처리 중 retryable 인프라 예외는 ACK하지 않고 남겨 재전달/reclaim 경로에서 복구한다.
                    log.error("traffic_stream_record_handle_failed recordId={} latency={}", recordId, e, latency);
                    return;
                }

                String dlqReason = buildNonRetryableDlqReason(e);
                handleTraceAwareNonRetryableFailure(
                        payload,
                        payloadJson,
                        recordId,
                        record.getId(),
                        e,
                        dlqReason,
                        dedupeCleanupRequired
                );
                resultTag = RESULT_DLQ;
            } finally {
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        } finally {
            // 전체 소요시간은 stage(tag=total)와 독립 total metric에 동시에 반영한다.
            // 분석 시 두 지표를 교차 확인하면 stage 집계 누락 여부를 쉽게 검증할 수 있다.
            long totalLatencyMs = elapsedSinceNs(handleStartNs);
            trafficRecordStageMetricsPort.recordStageLatency(STAGE_TOTAL, totalLatencyMs);
            trafficRecordStageMetricsPort.recordTotalLatency(totalLatencyMs);
            trafficRecordStageMetricsPort.incrementResult(resultTag);
        }
    }

    /**
     * reclaim 재시도 횟수가 상한을 넘은 메시지를 종결 처리합니다.
     * 순서는 정책에 맞춰 "DLQ 기록 -> dedupe 삭제 outbox 적재 -> ACK"로 유지합니다.
     */
    private void handleReclaimRetryExceeded(
            TrafficPayloadReqDto payload,
            String payloadJson,
            String recordId,
            RecordId streamRecordId,
            int retryCountAfterIncrement
    ) {
        // 1) 재처리 한도 초과 사유를 DLQ에 남겨 후속 분석/수동 복구 기준으로 사용한다.
        trafficStreamInfraService.writeDlq(
                payloadJson,
                buildReclaimRetryExceededDlqReason(retryCountAfterIncrement),
                recordId
        );
        // 2) 멱등키 정리는 outbox 경로로만 진행하고, 적재 직후 즉시 삭제 시도는 outbox 서비스가 담당한다.
        trafficInFlightDedupeDeleteOutboxService.createPending(payload.getTraceId(), recordId);
        // 3) 마지막에 ACK해 동일 레코드가 재배달되지 않도록 종결한다.
        acknowledgeWithMetrics(streamRecordId);

        log.warn(
                "traffic_stream_reclaim_retry_exceeded trace_id={} record_id={} retry_count={} threshold={}",
                payload.getTraceId(),
                recordId,
                retryCountAfterIncrement,
                RECLAIM_RETRY_EXCEEDED_THRESHOLD
        );
    }

    /**
     * reclaim retry 초과 DLQ 사유 문자열을 생성합니다.
     */
    private String buildReclaimRetryExceededDlqReason(int retryCountAfterIncrement) {
        return String.format(
                "reclaim retry exceeded: retryCount=%d, threshold=%d",
                retryCountAfterIncrement,
                RECLAIM_RETRY_EXCEEDED_THRESHOLD
        );
    }

    /**
     * traceId 확보 non-retryable 예외를 DLQ + ACK 순서로 종결합니다.
     */
    private void handleTraceAwareNonRetryableFailure(
            TrafficPayloadReqDto payload,
            String payloadJson,
            String recordId,
            RecordId streamRecordId,
            Exception exception,
            String dlqReason,
            boolean dedupeCleanupRequired
    ) {
        String summarizedErrorMessage = summarizeException(exception);

        trafficStreamInfraService.writeDlq(payloadJson, dlqReason, recordId);
        if (dedupeCleanupRequired) {
            trafficInFlightDedupeDeleteOutboxService.createPending(payload.getTraceId(), recordId);
        }
        acknowledgeWithMetrics(streamRecordId);
        log.warn(
                "traffic_stream_non_retryable_terminated trace_id={} record_id={} dlq_reason={} summary={}",
                payload.getTraceId(),
                recordId,
                dlqReason,
                summarizedErrorMessage
        );
    }

    /**
     * non-retryable 예외의 DLQ 사유 문자열을 생성합니다.
     */
    private String buildNonRetryableDlqReason(Exception exception) {
        return "non-retryable failure: " + summarizeException(exception);
    }

    /**
     * 예외 타입 + 핵심 메시지 형태로 요약 문자열을 생성합니다.
     */
    private String summarizeException(Throwable throwable) {
        if (throwable == null) {
            return "UnknownException";
        }

        String exceptionName = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return exceptionName;
        }

        String compactMessage = message.replaceAll("\\s+", " ").trim();
        return exceptionName + ": " + compactMessage;
    }

    /**
     * ACK 호출의 소요시간을 단계 메트릭(stage=ack)으로 기록합니다.
     *
     * @param recordId ACK 대상 레코드 ID
     */
    private void acknowledgeWithMetrics(RecordId recordId) {
        long ackStartNs = System.nanoTime();
        try {
            trafficStreamInfraService.acknowledge(recordId);
        } finally {
            trafficRecordStageMetricsPort.recordStageLatency(STAGE_ACK, elapsedSinceNs(ackStartNs));
        }
    }

    /**
     * System.nanoTime 기반 경과 시간을 밀리초로 계산합니다.
     *
     * @param startNs 시작 시점 nanoTime
     * @return 시작 시점 이후 경과 밀리초
     */
    private long elapsedSinceNs(long startNs) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startNs);
        return TimeUnit.NANOSECONDS.toMillis(elapsedNs);
    }

    /**
     * 재개량이 0인 경우 오케스트레이션 호출 없이 사용할 no-op 실행 결과를 생성합니다.
     *
     * @param traceId 요청 추적 ID
     * @return 차감량 0 기준 성공 결과
     */
    private TrafficDeductResultResDto buildNoopExecutionResult(String traceId) {
        LocalDateTime now = LocalDateTime.now();
        return TrafficDeductResultResDto.builder()
                .traceId(traceId)
                .apiTotalData(0L)
                .deductedIndividualBytes(0L)
                .deductedSharedBytes(0L)
                .apiRemainingData(0L)
                .finalStatus(TrafficFinalStatus.SUCCESS)
                .lastLuaStatus(TrafficLuaStatus.OK)
                .createdAt(now)
                .finishedAt(now)
                .build();
    }

    /**
     * done log 저장 직전 누적 처리량 기준으로 최종 결과를 재조립합니다.
     *
     * <p>핵심 규칙:
     * 1) `deductedIndividualBytes`/`deductedSharedBytes`/`apiRemainingData`는
     *    이번 실행 증분값이 아니라 Redis dedupe 누적값을 기준으로 계산합니다.
     * 2) `executionResult`는 마지막 Lua 상태(`lastLuaStatus`)와 시각 정보(`createdAt`/`finishedAt`) 전달에 사용합니다.
     * 3) Redis dedupe 조회 결과가 비어 있으면(예: traceId 공백, key 미존재)
     *    `processedBefore + deductedThisRun` 계산값을 fallback으로 사용합니다.
     *    Redis 조회 중 예외가 발생하면 fallback하지 않고 상위로 전파합니다.
     *
     * @param originalPayload 원본 요청 payload
     * @param executionResult 이번 시도 실행 결과(상태/시각 전달용)
     * @param processedIndividualDataBefore 이번 시도 전 개인풀 누적 처리량
     * @param processedSharedDataBefore 이번 시도 전 공유풀 누적 처리량
     * @return done log 저장에 사용할 누적 기준 결과
     */
    private TrafficDeductResultResDto buildCumulativeResult(
            TrafficPayloadReqDto originalPayload,
            TrafficDeductResultResDto executionResult,
            long processedIndividualDataBefore,
            long processedSharedDataBefore
    ) {
        long originalApiTotalData = normalizeNonNegative(originalPayload == null ? null : originalPayload.getApiTotalData());
        String traceId = originalPayload == null ? null : originalPayload.getTraceId();
        long cumulativeIndividual;
        long cumulativeShared;

        // done log 직전 기준값은 Redis dedupe 누적량을 우선 사용한다.
        Optional<TrafficInFlightIdempotencyEntry> dedupeEntry = trafficInFlightDedupeService.get(traceId);
        if (dedupeEntry.isPresent()) {
            TrafficInFlightIdempotencyEntry entry = dedupeEntry.get();
            cumulativeIndividual = normalizeNonNegative(entry.processedIndividualData());
            cumulativeShared = normalizeNonNegative(entry.processedSharedData());
        } else {
            // dedupe 조회 결과가 비어 있으면: 직전 누적값 + 이번 실행 증분값으로 누적량을 복원한다.
            long deductedIndividualThisRun = normalizeNonNegative(
                    executionResult == null ? null : executionResult.getDeductedIndividualBytes()
            );
            long deductedSharedThisRun = normalizeNonNegative(
                    executionResult == null ? null : executionResult.getDeductedSharedBytes()
            );
            cumulativeIndividual = normalizeNonNegative(processedIndividualDataBefore) + deductedIndividualThisRun;
            cumulativeShared = normalizeNonNegative(processedSharedDataBefore) + deductedSharedThisRun;
        }

        long cumulativeDeducted = resolveCumulativeDeducted(
                originalApiTotalData,
                cumulativeIndividual + cumulativeShared,
                0L
        );
        long cumulativeRemaining = clampRemaining(originalApiTotalData - cumulativeDeducted);

        // 최종 상태는 "이번 실행"이 아니라 누적 차감 결과(누적 차감량/남은량)로 판정한다.
        TrafficLuaStatus lastLuaStatus = executionResult == null ? null : executionResult.getLastLuaStatus();
        TrafficFinalStatus finalStatus = resolveCumulativeFinalStatus(
                originalApiTotalData,
                cumulativeDeducted,
                cumulativeRemaining,
                lastLuaStatus
        );
        LocalDateTime now = LocalDateTime.now();

        return TrafficDeductResultResDto.builder()
                .traceId(originalPayload == null ? null : originalPayload.getTraceId())
                .apiTotalData(originalApiTotalData)
                .deductedIndividualBytes(cumulativeIndividual)
                .deductedSharedBytes(cumulativeShared)
                .apiRemainingData(cumulativeRemaining)
                .finalStatus(finalStatus)
                .lastLuaStatus(lastLuaStatus)
                .createdAt(defaultNowIfNull(executionResult == null ? null : executionResult.getCreatedAt(), now))
                .finishedAt(defaultNowIfNull(executionResult == null ? null : executionResult.getFinishedAt(), now))
                .build();
    }

    /**
     * 누적 남은량과 마지막 Lua 상태로 최종 상태를 계산합니다.
     *
     * @param cumulativeRemaining 누적 계산 후 남은량
     * @param lastLuaStatus 마지막 Lua 실행 상태
     * @return 최종 처리 상태
     */
    private TrafficFinalStatus resolveCumulativeFinalStatus(
            long originalApiTotalData,
            long cumulativeDeducted,
            long cumulativeRemaining,
            TrafficLuaStatus lastLuaStatus
    ) {
        if (lastLuaStatus == TrafficLuaStatus.ERROR) {
            return TrafficFinalStatus.FAILED;
        }
        if (cumulativeDeducted <= 0L && cumulativeRemaining == originalApiTotalData) {
            return TrafficFinalStatus.NOT_DEDUCTED;
        }
        if (cumulativeRemaining <= 0L) {
            return TrafficFinalStatus.SUCCESS;
        }
        return TrafficFinalStatus.PARTIAL_SUCCESS;
    }

    /**
     * null/음수 입력을 0 이상 값으로 보정합니다.
     *
     * @param value 보정 대상 값
     * @return 0 이상으로 정규화된 값
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0L) {
            return 0L;
        }
        return value;
    }

    /**
     * 음수 잔량을 0으로 보정합니다.
     *
     * @param value 보정 대상 잔량
     * @return 0 이상 잔량
     */
    private long clampRemaining(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return value;
    }

    /**
     * 누적 차감량을 계산하고 불변식(apiTotalData 초과 금지)을 검증합니다.
     *
     * @param originalApiTotalData 원본 요청량
     * @param processedDataBefore 이전까지 누적 처리량
     * @param deductedThisRun 이번 실행 차감량
     * @return 검증을 통과한 누적 차감량
     * @throws CumulativeInvariantViolationException 누적 차감량이 원본 요청량을 초과한 경우
     */
    private long resolveCumulativeDeducted(
            long originalApiTotalData,
            long processedDataBefore,
            long deductedThisRun
    ) {
        long safeProcessedDataBefore = normalizeNonNegative(processedDataBefore);
        long cumulativeDeducted = safeProcessedDataBefore + deductedThisRun;
        if (cumulativeDeducted > originalApiTotalData) {
            throw new CumulativeInvariantViolationException(
                    String.format(
                            "cumulativeDeducted(%d) exceeds apiTotalData(%d); processedBefore=%d deductedThisRun=%d",
                            cumulativeDeducted,
                            originalApiTotalData,
                            safeProcessedDataBefore,
                            deductedThisRun
                    )
            );
        }
        return cumulativeDeducted;
    }

    /**
     * 값이 null이면 전달받은 시각으로 대체합니다.
     *
     * @param value 원본 시각
     * @param fallbackNow 대체 시각
     * @return null 이면 fallbackNow, 아니면 value
     */
    private LocalDateTime defaultNowIfNull(LocalDateTime value, LocalDateTime fallbackNow) {
        if (value == null) {
            return fallbackNow;
        }
        return value;
    }

    /**
     * 유효성 검증 위반 목록을 단일 로그/사유 문자열로 직렬화합니다.
     *
     * @param violations 위반 목록
     * @return DLQ 사유에 저장할 요약 문자열
     */
    private String buildValidationFailureReason(List<Violation> violations) {
        return "payload validation failed: " + violations.stream()
                .map(violation -> violation.getName() + "=" + violation.getReason())
                .collect(Collectors.joining(", "));
    }

    /**
     * 설정된 worker 스레드 수를 읽고 최소 1로 보정합니다.
     *
     * @return 실제 사용할 worker 스레드 수
     */
    private int resolveWorkerThreadCount() {
        int configuredCount = appStreamsProperties.getWorkerThreadCount();
        if (configuredCount > 0) {
            return configuredCount;
        }

        // 잘못된 설정값(0 이하)이 들어오면 최소 1개 스레드로 안전하게 보정한다.
        return 1;
    }

    /**
     * 현재 worker 가용량을 기준으로 다음 read count를 계산합니다.
     *
     * @return 이번 사이클에서 읽을 최대 레코드 수
     */
    private int resolveNextReadCount() {
        if (workerExecutor == null) {
            return 0;
        }

        int configuredReadCount = appStreamsProperties.requireReadCount();
        int availableWorkerSlots = Math.max(0, workerExecutor.getMaximumPoolSize() - workerExecutor.getActiveCount());
        int queueRemainingCapacity = workerExecutor.getQueue().remainingCapacity();
        int dispatchCapacity = availableWorkerSlots + queueRemainingCapacity;

        if (dispatchCapacity <= 0) {
            return 0;
        }

        return Math.min(configuredReadCount, dispatchCapacity);
    }

    /**
     * 현재 워커 풀 상태를 읽어 Prometheus Gauge 값으로 반영합니다.
     */
    private void publishWorkerRuntimeMetrics() {
        if (workerExecutor == null) {
            trafficGeneratorMetrics.updateWorkerIdleThreads(0);
            trafficGeneratorMetrics.updateWorkerQueueSize(0);
            return;
        }

        int idleWorkers = Math.max(0, workerExecutor.getMaximumPoolSize() - workerExecutor.getActiveCount());
        int queuedTasks = Math.max(0, workerExecutor.getQueue().size());

        trafficGeneratorMetrics.updateWorkerIdleThreads(idleWorkers);
        trafficGeneratorMetrics.updateWorkerQueueSize(queuedTasks);
    }

    /**
     * worker 풀 포화 상태 진입 시 1회성 경고 로그를 남깁니다.
     */
    private void signalWorkerPressure() {
        if (workerExecutor == null) {
            return;
        }

        if (workerPressureActive.compareAndSet(false, true)) {
            log.warn(
                    "traffic_stream_worker_pressure_on activeWorkers={} workerThreads={} queueSize={} queueCapacity={} rejectionPolicy={}",
                    workerExecutor.getActiveCount(),
                    workerExecutor.getMaximumPoolSize(),
                    workerExecutor.getQueue().size(),
                    workerExecutor.getQueue().remainingCapacity() + workerExecutor.getQueue().size(),
                    appStreamsProperties.requireWorkerRejectionPolicy()
            );
        }
    }

    /**
     * worker 풀 포화 상태가 해제되면 해제 로그를 남깁니다.
     */
    private void clearWorkerPressureSignal() {
        if (workerExecutor == null) {
            return;
        }

        if (workerPressureActive.compareAndSet(true, false)) {
            log.info(
                    "traffic_stream_worker_pressure_off activeWorkers={} workerThreads={} queueSize={} queueCapacity={}",
                    workerExecutor.getActiveCount(),
                    workerExecutor.getMaximumPoolSize(),
                    workerExecutor.getQueue().size(),
                    workerExecutor.getQueue().remainingCapacity() + workerExecutor.getQueue().size()
            );
        }
    }

    /**
     * worker 처리 여유가 생길 때까지 짧게 대기합니다.
     */
    private void pauseForWorkerCapacity() {
        int retryAttempt = Math.max(1, workerPressureRetryAttempt + 1);
        workerPressureRetryAttempt = retryAttempt;
        try {
            Thread.sleep(resolvePressurePauseMs(retryAttempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 포화 상태 대기 시간을 계산합니다.
     *
     * @return 밀리초 단위 대기 시간
     */
    private long resolvePressurePauseMs(int retryAttempt) {
        long delayMs = TrafficRetryBackoffSupport.resolveDelayMs(retryBackoffMs, retryAttempt);
        return Math.min(250L, Math.max(0L, delayMs));
    }

    /**
     * 종료 요청 후 대기 및 강제 종료를 포함해 executor를 정리합니다.
     *
     * @param executorName 로그 식별용 executor 이름
     * @param executorService 종료 대상 executor
     * @param awaitMs 정상 종료 대기 시간(ms)
     */
    private void shutdownExecutor(String executorName, ExecutorService executorService, long awaitMs) {
        if (executorService == null) {
            return;
        }

        executorService.shutdown();
        if (awaitTermination(executorName, executorService, awaitMs)) {
            return;
        }

        List<Runnable> droppedTasks = executorService.shutdownNow();
        awaitTermination(executorName, executorService, 0L);
        if (!droppedTasks.isEmpty()) {
            log.warn("traffic_stream_executor_forced_stop executor={} droppedTasks={}", executorName, droppedTasks.size());
        }
    }

    /**
     * 지정한 시간만큼 executor 종료를 기다립니다.
     *
     * @param executorName 로그 식별용 executor 이름
     * @param executorService 대기 대상 executor
     * @param awaitMs 대기 시간(ms)
     * @return 제한 시간 내 종료되면 {@code true}
     */
    private boolean awaitTermination(String executorName, ExecutorService executorService, long awaitMs) {
        try {
            if (awaitMs <= 0L) {
                return executorService.awaitTermination(0L, TimeUnit.MILLISECONDS);
            }

            boolean terminated = executorService.awaitTermination(awaitMs, TimeUnit.MILLISECONDS);
            if (!terminated) {
                log.warn("traffic_stream_executor_shutdown_timeout executor={} awaitMs={}", executorName, awaitMs);
            }
            return terminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("traffic_stream_executor_shutdown_interrupted executor={}", executorName);
            return false;
        }
    }

    /**
     * 설정된 worker rejection 정책에 맞는 handler를 구성합니다.
     *
     * @param rejectionPolicy 설정된 거절 정책
     * @return 스레드풀에 적용할 RejectedExecutionHandler
     */
    private RejectedExecutionHandler buildRejectedExecutionHandler(WorkerRejectionPolicy rejectionPolicy) {
        if (rejectionPolicy == WorkerRejectionPolicy.CALLER_RUNS) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return new ThreadPoolExecutor.AbortPolicy();
    }
}
