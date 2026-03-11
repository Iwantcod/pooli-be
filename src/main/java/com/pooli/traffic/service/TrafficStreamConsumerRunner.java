package com.pooli.traffic.service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Streams BLOCK 소비 루프를 실행하는 러너입니다.
 * poller 스레드는 레코드를 읽고, worker 풀은 레코드 처리 로직을 병렬 수행합니다.
 * worker는 payload 역직렬화 후 10-tick 오케스트레이터를 호출합니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamConsumerRunner implements SmartLifecycle {

    // Streams read/ack/DLQ 인프라 유틸
    private final TrafficStreamInfraService trafficStreamInfraService;
    // app.streams.* 설정값
    private final AppStreamsProperties appStreamsProperties;
    // payload JSON 역직렬화 도구
    private final ObjectMapper objectMapper;
    // 10-tick 차감 오케스트레이션 서비스
    private final TrafficDeductOrchestratorService trafficDeductOrchestratorService;
    // in-flight dedupe 선점 서비스(traceId 기준)
    private final TrafficInFlightDedupeService trafficInFlightDedupeService;

    // 전역적인 소비 루프 동작 여부 플래그(start/stop 간 공유)
    private final AtomicBoolean running = new AtomicBoolean(false);
    // BLOCK read 전용 단일 poller 실행기
    private ExecutorService pollerExecutor;
    // 레코드 처리 병렬 수행용 worker 실행기
    private ExecutorService workerExecutor;

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

        // poller는 BLOCK read 전용이므로 단일 스레드면 충분하다.
        pollerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "traffic-stream-poller");
            thread.setDaemon(true);
            return thread;
        });

        // 레코드 처리는 worker 풀에서 병렬 수행한다.
        workerExecutor = Executors.newFixedThreadPool(workerThreadCount, r -> {
            Thread thread = new Thread(r, "traffic-stream-worker");
            thread.setDaemon(true);
            return thread;
        });

        // running 플래그를 먼저 켠 뒤 루프를 제출해
        // 제출 직후 루프가 즉시 종료되는 경쟁 상태를 피한다.
        running.set(true);
        pollerExecutor.submit(this::consumeLoop);
        log.info(
                "traffic_stream_consumer_started group={} consumer={} workerThreads={}",
                appStreamsProperties.getGroupTraffic(),
                appStreamsProperties.getConsumerName(),
                workerThreadCount
        );
    }

    @Override
    public void stop() {
        // 루프가 다음 사이클에서 종료되도록 먼저 상태를 내린다.
        running.set(false);

        // BLOCK read 대기를 빠르게 종료하기 위해 poller를 먼저 interrupt한다.
        if (pollerExecutor != null) {
            pollerExecutor.shutdownNow();
        }

        // 진행 중/대기 중인 처리 태스크를 함께 종료시킨다.
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
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
        // SmartLifecycle stop() 호출 전까지 BLOCK read -> 레코드 처리 과정을 반복한다.
        while (running.get()) {
            try {
                List<MapRecord<String, String, String>> records = trafficStreamInfraService.readBlocking();

                // BLOCK read 특성상 대부분의 루프는 0건 또는 소수 레코드를 받는다.
                // 수신된 레코드는 worker 풀로 분배해 병렬 처리한다.
                for (MapRecord<String, String, String> record : records) {
                    dispatchRecord(record);
                }
            } catch (Exception e) {
                // 루프를 중단하지 않고 다음 read 사이클로 복구를 시도한다.
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
            // poller는 I/O(read)에 집중하고, 처리 비용이 있는 역직렬화/분기는 worker로 위임한다.
            workerExecutor.submit(() -> handleRecord(record));
        } catch (RejectedExecutionException e) {
            // 종료 과정에서 발생 가능한 submit 실패는 로그만 남기고 무해하게 처리한다.
            log.warn(
                    "traffic_stream_record_dispatch_rejected recordId={} running={}",
                    record.getId().getValue(),
                    running.get()
            );
        }
    }

    private void handleRecord(MapRecord<String, String, String> record) {
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

            // traceId가 없으면 dedupe 키를 만들 수 없고 재처리 제어가 불가능하므로
            // 복구 불가능한 메시지로 판단해 DLQ + ACK 처리한다.
            if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
                trafficStreamInfraService.writeDlq(payloadJson, "traceId가 비어 있습니다.", recordId);
                trafficStreamInfraService.acknowledge(record.getId());
                return;
            }

            // 동일 traceId에 대한 동시/중복 처리 경쟁을 막기 위해 in-flight 선점을 시도한다.
            // 선점 실패는 이미 다른 워커(또는 다른 인스턴스)가 처리 중이라는 의미이므로
            // 현재 레코드는 실행을 생략한다.
            boolean claimed = trafficInFlightDedupeService.tryClaim(payload.getTraceId());
            if (!claimed) {
                log.info(
                        "traffic_stream_record_deduped recordId={} traceId={}",
                        recordId,
                        payload.getTraceId()
                );
                return;
            }

            // 10-tick 오케스트레이터를 실행해 개인풀/공유풀 차감 결과를 계산한다.
            TrafficDeductResultResDto result = trafficDeductOrchestratorService.orchestrate(payload);

            // 현재 단계는 DONE 영속화/ACK 순서(3.11) 전이므로 ACK는 아직 수행하지 않는다.
            // 우선 결과를 로그로 남겨 흐름을 검증한다.
            log.info(
                    "traffic_stream_record_orchestrated recordId={} traceId={} finalStatus={} deducted={} remaining={} lastLuaStatus={}",
                    recordId,
                    result.getTraceId(),
                    result.getFinalStatus(),
                    result.getDeductedTotalBytes(),
                    result.getApiRemainingData(),
                    result.getLastLuaStatus()
            );
        } catch (JsonProcessingException e) {
            // 스키마 불일치/JSON 파손은 재처리해도 복구가 어려우므로 DLQ로 분기한다.
            trafficStreamInfraService.writeDlq(payloadJson, "payload 역직렬화 실패", recordId);
            trafficStreamInfraService.acknowledge(record.getId());
        }
    }

    private int resolveWorkerThreadCount() {
        int configuredCount = appStreamsProperties.getWorkerThreadCount();
        if (configuredCount > 0) {
            return configuredCount;
        }

        // 잘못된 설정값(0 이하)이 들어오면 최소 1개 스레드로 안전하게 보정한다.
        return 1;
    }
}
