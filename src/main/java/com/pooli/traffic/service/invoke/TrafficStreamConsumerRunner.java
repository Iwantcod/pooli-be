package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.dto.Violation;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamConsumerRunner implements SmartLifecycle {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final TrafficStreamInfraService trafficStreamInfraService;
    private final AppStreamsProperties appStreamsProperties;
    private final ObjectMapper objectMapper;
    private final TrafficPayloadValidationService trafficPayloadValidationService;
    private final TrafficDeductOrchestratorService trafficDeductOrchestratorService;
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;
    private final TrafficDeductDoneLogService trafficDeductDoneLogService;
    private final TrafficStreamReclaimService trafficStreamReclaimService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService pollerExecutor;
    private ExecutorService workerExecutor;
    private ScheduledExecutorService reclaimExecutor;

    @Override
    public void start() {
        if (!appStreamsProperties.isConsumerEnabled()) {
            log.info("traffic_stream_consumer_disabled enabled=false");
            return;
        }

        trafficStreamInfraService.ensureConsumerGroup();

        int workerThreadCount = resolveWorkerThreadCount();

        pollerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "traffic-stream-poller");
            thread.setDaemon(false);
            return thread;
        });

        workerExecutor = Executors.newFixedThreadPool(workerThreadCount, r -> {
            Thread thread = new Thread(r, "traffic-stream-worker");
            thread.setDaemon(false);
            return thread;
        });

        running.set(true);
        pollerExecutor.submit(this::consumeLoop);
        startReclaimLoop();
        log.info(
                "traffic_stream_consumer_started group={} consumer={} workerThreads={}",
                appStreamsProperties.getGroupTraffic(),
                appStreamsProperties.getConsumerName(),
                workerThreadCount
        );
    }

    @Override
    public void stop() {
        running.set(false);

        if (pollerExecutor != null) {
            pollerExecutor.shutdownNow();
        }

        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }

        if (reclaimExecutor != null) {
            reclaimExecutor.shutdownNow();
        }

        log.info("traffic_stream_consumer_stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                List<MapRecord<String, String, String>> records = trafficStreamInfraService.readBlocking();
                for (MapRecord<String, String, String> record : records) {
                    dispatchRecord(record);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.error("traffic_stream_consume_loop_failed", e);
            }
        }
    }

    private void dispatchRecord(MapRecord<String, String, String> record) {
        if (workerExecutor == null) {
            log.warn("traffic_stream_worker_not_ready recordId={}", record.getId().getValue());
            return;
        }

        try {
            workerExecutor.submit(() -> handleRecord(record));
        } catch (RejectedExecutionException e) {
            log.warn(
                    "traffic_stream_record_dispatch_rejected recordId={} running={}",
                    record.getId().getValue(),
                    running.get()
            );
        }
    }

    private void startReclaimLoop() {
        long reclaimIntervalMs = Math.max(1L, appStreamsProperties.getReclaimIntervalMs());

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

    private void runReclaimCycle() {
        if (!running.get()) {
            return;
        }

        try {
            List<MapRecord<String, String, String>> reclaimedRecords =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            for (MapRecord<String, String, String> reclaimedRecord : reclaimedRecords) {
                dispatchRecord(reclaimedRecord);
            }
        } catch (Exception e) {
            log.error("traffic_stream_reclaim_cycle_failed", e);
        }
    }

    private void handleRecord(MapRecord<String, String, String> record) {
        MDC.remove(TRACE_ID_MDC_KEY);

        String recordId = record.getId().getValue();
        String payloadJson = trafficStreamInfraService.extractPayload(record);

        if (payloadJson == null || payloadJson.isBlank()) {
            trafficStreamInfraService.writeDlq(payloadJson, "payload 필드가 비어 있습니다.", recordId);
            trafficStreamInfraService.acknowledge(record.getId());
            return;
        }

        try {
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

                TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);
                boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload, result, recordId);

                trafficStreamInfraService.acknowledge(record.getId());

                LocalDateTime loggedAt = LocalDateTime.now();
                String logEventName = saved ? "traffic_stream_record_done" : "traffic_stream_record_done_duplicate";
                log.info(
                        logEventName + " "
                                + "trace_id={} record_id={} line_id={} family_id={} app_id={} "
                                + "api_total_data={} deducted_total_bytes={} api_remaining_data={} "
                                + "final_status={} last_lua_status={} created_at={} finished_at={} logged_at={}",
                        traceId,
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
                        loggedAt
                );
            } catch (Exception e) {
                log.error("traffic_stream_record_handle_failed recordId={}", recordId, e);
            } finally {
                if (claimAcquired) {
                    // Failed records stay pending, so release the in-flight marker to let reclaim retry them.
                    trafficInFlightDedupeService.release(traceId);
                }
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        } catch (JsonProcessingException e) {
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
        return 1;
    }
}
