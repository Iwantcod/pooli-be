package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;
import java.util.List;
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

import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.config.AppStreamsProperties.WorkerRejectionPolicy;
import com.pooli.common.dto.Violation;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

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
    // Mongo 완료 로그 서비스(traceId UNIQUE idempotency)
    private final TrafficDeductDoneLogService trafficDeductDoneLogService;
    // pending reclaim/retry/DLQ 분기 서비스
    private final TrafficStreamReclaimService trafficStreamReclaimService;

    // 전역적인 소비 루프 동작 여부 플래그(start/stop 간 공유)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean workerPressureActive = new AtomicBoolean(false);

    private ExecutorService pollerExecutor;
    private ThreadPoolExecutor workerExecutor;
    private ScheduledExecutorService reclaimExecutor;

    @Override
    /**
     * 애플리케이션 시작 시점에 필요한 초기화 작업을 수행합니다.
     */
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

    @Override
    /**
     * 애플리케이션 종료 시점에 실행 중인 리소스를 안전하게 정리합니다.
     */
    public void stop() {
        // 루프가 다음 사이클에서 종료되도록 먼저 상태를 내린다.
        running.set(false);

        shutdownExecutor("traffic-stream-reclaim", reclaimExecutor, 0L);
        shutdownExecutor("traffic-stream-poller", pollerExecutor, 0L);
        shutdownExecutor("traffic-stream-worker", workerExecutor, appStreamsProperties.requireShutdownAwaitMs());

        log.info("traffic_stream_consumer_stopped");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    /**
     * 현재 상태를 불리언 값으로 확인해 호출 측의 분기 판단을 돕습니다.
     */
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    /**
     * 현재 설정/상태 값을 반환합니다.
     */
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
      * `consumeLoop` 처리 목적에 맞는 핵심 로직을 수행합니다.
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

    private void consumeNextBatch() {
        int nextReadCount = resolveNextReadCount();
        if (nextReadCount <= 0) {
            signalWorkerPressure();
            pauseForWorkerCapacity();
            return;
        }

        clearWorkerPressureSignal();

        List<MapRecord<String, String, String>> records = trafficStreamInfraService.readBlocking(nextReadCount);
        for (MapRecord<String, String, String> record : records) {
            dispatchRecord(record);
        }
    }

    private void dispatchRecord(MapRecord<String, String, String> record) {
        if (!running.get()) {
            log.info("traffic_stream_record_dispatch_skipped recordId={} reason=stopping", record.getId().getValue());
            return;
        }

        if (workerExecutor == null) {
            log.warn("traffic_stream_worker_not_ready recordId={}", record.getId().getValue());
            return;
        }

        try {
            workerExecutor.execute(() -> handleRecord(record));
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
        }
    }

    /**
      * 컴포넌트 실행을 시작하고 필요한 초기화를 수행합니다.
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
      * `runReclaimCycle` 처리 목적에 맞는 핵심 로직을 수행합니다.
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
                dispatchRecord(reclaimedRecord);
            }
        } catch (Exception e) {
            // reclaim 실패가 메인 소비 루프를 멈추게 해서는 안 되므로 로그 후 다음 주기를 기다린다.
            log.error("traffic_stream_reclaim_cycle_failed", e);
        }
    }

    /**
      * 입력 상태를 해석해 분기별 처리 로직을 수행합니다.
     */
    private void handleRecord(MapRecord<String, String, String> record) {
        long consumeStartTimeMs = System.currentTimeMillis();
        // worker 스레드는 재사용되므로 이전 레코드의 MDC가 남지 않게 먼저 비운다.
        MDC.remove(TRACE_ID_MDC_KEY);

        // DLQ/로그 추적을 위해 레코드 ID를 초기에 추출해 둔다.
        String recordId = record.getId().getValue();

        // 명세(field=payload)에 맞춰 payload 문자열을 가져온다.
        String payloadJson = trafficStreamInfraService.extractPayload(record);

        if (payloadJson == null || payloadJson.isBlank()) {
            // payload 자체가 없으면 이후 처리 불가능하므로 DLQ로 우회 후 ACK한다.
            trafficStreamInfraService.writeDlq(payloadJson, "payload 필드가 비어 있습니다.", recordId);
            trafficStreamInfraService.acknowledge(record.getId());
            return;
        }

        try {
            // JSON payload를 DTO로 역직렬화해 이후 오케스트레이터가 바로 사용할 수 있게 한다.
            TrafficPayloadReqDto payload = objectMapper.readValue(payloadJson, TrafficPayloadReqDto.class);
            List<Violation> violations = trafficPayloadValidationService.validate(payload);
            if (!violations.isEmpty()) {
                trafficStreamInfraService.writeDlq(payloadJson, buildValidationFailureReason(violations), recordId);
                trafficStreamInfraService.acknowledge(record.getId());
                return;
            }

            String traceId = payload.getTraceId();
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            boolean claimAcquired = false;
            try {
                if (trafficDeductDoneLogService.existsByTraceId(traceId)) {
                    log.info("traffic_stream_record_already_done recordId={}", recordId);
                    trafficStreamInfraService.acknowledge(record.getId());
                    return;
                }

                claimAcquired = trafficInFlightDedupeService.tryClaim(traceId);
                if (!claimAcquired) {
                    if (trafficDeductDoneLogService.existsByTraceId(traceId)) {
                        trafficStreamInfraService.acknowledge(record.getId());
                    }
                    log.info("traffic_stream_record_deduped recordId={}", recordId);
                    return;
                }

                // 이벤트 단위 오케스트레이터를 실행해 개인풀/공유풀 차감 결과를 계산한다.
                TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

                // Mongo 완료 로그 저장을 먼저 수행한다.
                // - 신규 저장(true) 또는 중복 저장(false)은 모두 idempotent 성공으로 간주한다.
                // - 저장 예외가 발생하면 ACK를 하지 않아 재전달로 복구한다.
                // latency는 레코드 처리 시작 시점부터 done-log 저장 직전까지의 ms를 사용한다.
                long latency = Math.max(0L, System.currentTimeMillis() - consumeStartTimeMs);
                boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload, result, recordId, latency);

                // "저장 성공 후 ACK" 규칙을 보장하기 위해 영속화 이후에만 ACK한다.
                trafficStreamInfraService.acknowledge(record.getId());

                // ACK까지 성공한 뒤 in-flight 키를 정리한다.
                trafficInFlightDedupeService.release(payload.getTraceId());

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
                        result.getApiTotalData(),
                        result.getDeductedTotalBytes(),
                        result.getApiRemainingData(),
                        result.getFinalStatus(),
                        result.getLastLuaStatus(),
                        result.getCreatedAt(),
                        result.getFinishedAt(),
                        loggedAt,
                        latency
                );
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - consumeStartTimeMs;
                // 처리 중 시스템 예외는 ACK하지 않고 남겨 재전달/reclaim 경로에서 복구한다.
                log.error("traffic_stream_record_handle_failed recordId={} latency={}", recordId, e, latency);
            } finally {
                if (claimAcquired) {
                    // Failed records stay pending, so release the in-flight marker to let reclaim retry them.
                    trafficInFlightDedupeService.release(traceId);
                }
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        } catch (JsonProcessingException e) {
            long latency = System.currentTimeMillis() - consumeStartTimeMs;
            log.error("MQ message schema invalid recordId={} latency={}", recordId, e, latency);
            // 스키마 불일치/JSON 파손은 재처리해도 복구가 어려우므로 DLQ로 분기한다.
            trafficStreamInfraService.writeDlq(payloadJson, "payload 역직렬화 실패", recordId);
            trafficStreamInfraService.acknowledge(record.getId());
        }
    }

    private String buildValidationFailureReason(List<Violation> violations) {
        return "payload validation failed: " + violations.stream()
                .map(violation -> violation.getName() + "=" + violation.getReason())
                .collect(Collectors.joining(", "));
    }

    private int resolveWorkerThreadCount() {
        int configuredCount = appStreamsProperties.getWorkerThreadCount();
        if (configuredCount > 0) {
            return configuredCount;
        }

        // 잘못된 설정값(0 이하)이 들어오면 최소 1개 스레드로 안전하게 보정한다.
        return 1;
    }

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

    private void pauseForWorkerCapacity() {
        try {
            Thread.sleep(resolvePressurePauseMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long resolvePressurePauseMs() {
        long configuredBlockMs = appStreamsProperties.requireBlockMs();
        return Math.min(250L, Math.max(25L, configuredBlockMs / 4L));
    }

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

    private RejectedExecutionHandler buildRejectedExecutionHandler(WorkerRejectionPolicy rejectionPolicy) {
        if (rejectionPolicy == WorkerRejectionPolicy.CALLER_RUNS) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return new ThreadPoolExecutor.AbortPolicy();
    }
}
