package com.pooli.common.config;

import java.util.Locale;
import java.util.Set;

import org.springframework.util.StringUtils;

import com.pooli.traffic.service.invoke.TrafficStreamBootstrapException;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Streams 소비/적재와 관련된 애플리케이션 설정값을 바인딩합니다.
 * 3단계에서는 producer가 사용하는 stream key를 우선 활용하고,
 * 이후 consumer/reclaim 단계에서 나머지 필드를 재사용합니다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.streams")
public class AppStreamsProperties {
    private static final String UNSET_CONSUMER_NAME_TOKEN = "__unset_streams_consumer_name__";
    private static final Set<String> INVALID_SHARED_CONSUMER_NAMES = Set.of(
            "default",
            "consumer",
            "traffic",
            "traffic-consumer",
            "pooli",
            "pooli-be",
            "localhost"
    );

    private String keyTrafficRequest;
    private String groupTraffic;
    private String consumerName;
    private boolean consumerEnabled;
    private int workerThreadCount;
    private int workerQueueCapacity;
    private String workerRejectionPolicy;
    private int readCount;
    private int metricsPendingScanCount;
    private long blockMs;
    private int reclaimPendingScanCount;
    private long reclaimIntervalMs;
    private long reclaimMinIdleMs;
    private long shutdownAwaitMs;
    private int maxRetry;
    private String keyTrafficDlq;

    public String requireTrafficRequestStreamKey() {
        return requireText(
                "app.streams.key-traffic-request",
                keyTrafficRequest,
                "stream key must not be blank."
        );
    }

    public String requireTrafficGroup() {
        return requireText(
                "app.streams.group-traffic",
                groupTraffic,
                "consumer group must not be blank."
        );
    }

    public String requireConsumerNameForBootstrap() {
        String normalizedConsumerName = requireText(
                "app.streams.consumer-name",
                consumerName,
                "consumer name must resolve to a unique per-instance value."
        );

        if (normalizedConsumerName.contains("${") || normalizedConsumerName.contains("}")) {
            throw invalidBootstrapConfig(
                    "app.streams.consumer-name",
                    "placeholder was not resolved: " + normalizedConsumerName
            );
        }

        if (normalizedConsumerName.contains(UNSET_CONSUMER_NAME_TOKEN)) {
            throw invalidBootstrapConfig(
                    "app.streams.consumer-name",
                    "consumer name did not resolve. Set STREAMS_CONSUMER_NAME or provide HOSTNAME/COMPUTERNAME."
            );
        }

        String lowered = normalizedConsumerName.toLowerCase(Locale.ROOT);
        if (INVALID_SHARED_CONSUMER_NAMES.contains(lowered)) {
            throw invalidBootstrapConfig(
                    "app.streams.consumer-name",
                    "shared/default value is not allowed: " + normalizedConsumerName
            );
        }

        String normalizedGroup = normalize(groupTraffic);
        if (normalizedConsumerName.equals(normalizedGroup)) {
            throw invalidBootstrapConfig(
                    "app.streams.consumer-name",
                    "consumer name must not match app.streams.group-traffic."
            );
        }

        String normalizedStreamKey = normalize(keyTrafficRequest);
        if (normalizedConsumerName.equals(normalizedStreamKey)) {
            throw invalidBootstrapConfig(
                    "app.streams.consumer-name",
                    "consumer name must not match app.streams.key-traffic-request."
            );
        }

        return normalizedConsumerName;
    }

    public int requireWorkerQueueCapacity() {
        int normalizedCapacity = workerQueueCapacity;
        if (normalizedCapacity <= 0) {
            throw invalidBootstrapConfig(
                    "app.streams.worker-queue-capacity",
                    "worker queue capacity must be greater than 0."
            );
        }
        return normalizedCapacity;
    }

    public WorkerRejectionPolicy requireWorkerRejectionPolicy() {
        String normalizedPolicy = requireText(
                "app.streams.worker-rejection-policy",
                workerRejectionPolicy,
                "worker rejection policy must not be blank."
        );

        try {
            return WorkerRejectionPolicy.valueOf(
                    normalizedPolicy
                            .trim()
                            .replace('-', '_')
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            throw invalidBootstrapConfig(
                    "app.streams.worker-rejection-policy",
                    "unsupported policy: " + normalizedPolicy + ". Supported values: abort, caller-runs."
            );
        }
    }

    public int requireReadCount() {
        if (readCount <= 0) {
            throw invalidBootstrapConfig(
                    "app.streams.read-count",
                    "read count must be greater than 0."
            );
        }
        return readCount;
    }

    public int requireReclaimPendingScanCount() {
        if (reclaimPendingScanCount <= 0) {
            throw invalidBootstrapConfig(
                    "app.streams.reclaim-pending-scan-count",
                    "reclaim pending scan count must be greater than 0."
            );
        }
        return reclaimPendingScanCount;
    }

    public long requireBlockMs() {
        if (blockMs <= 0L) {
            throw invalidBootstrapConfig(
                    "app.streams.block-ms",
                    "block timeout must be greater than 0."
            );
        }
        return blockMs;
    }

    public long requireReclaimIntervalMs() {
        if (reclaimIntervalMs <= 0L) {
            throw invalidBootstrapConfig(
                    "app.streams.reclaim-interval-ms",
                    "reclaim interval must be greater than 0."
            );
        }
        return reclaimIntervalMs;
    }

    public long requireReclaimMinIdleMs() {
        if (reclaimMinIdleMs < 0L) {
            throw invalidBootstrapConfig(
                    "app.streams.reclaim-min-idle-ms",
                    "reclaim min idle must be 0 or greater."
            );
        }
        return reclaimMinIdleMs;
    }

    public long requireShutdownAwaitMs() {
        if (shutdownAwaitMs < 0L) {
            throw invalidBootstrapConfig(
                    "app.streams.shutdown-await-ms",
                    "shutdown await must be 0 or greater."
            );
        }
        return shutdownAwaitMs;
    }

    public int requireMaxRetry() {
        if (maxRetry < 0) {
            throw invalidBootstrapConfig(
                    "app.streams.max-retry",
                    "max retry must be 0 or greater."
            );
        }
        return maxRetry;
    }

    private String requireText(String propertyName, String value, String detail) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            throw invalidBootstrapConfig(propertyName, detail);
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim();
    }

    private TrafficStreamBootstrapException invalidBootstrapConfig(String propertyName, String detail) {
        return new TrafficStreamBootstrapException("Invalid " + propertyName + ": " + detail);
    }

    public enum WorkerRejectionPolicy {
        ABORT,
        CALLER_RUNS
    }
}
