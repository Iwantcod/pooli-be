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
    private static final long RECLAIM_MIN_IDLE_MULTIPLIER_NUMERATOR = 3L;
    private static final long RECLAIM_MIN_IDLE_MULTIPLIER_DENOMINATOR = 2L;
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
    /**
     * reclaim 최소 유휴 시간 계산용 입력값(최악 메시지 처리시간, ms)입니다.
     * 0 이하일 때는 reclaimMinIdleMs 설정값을 그대로 사용합니다.
     */
    private long reclaimWorstProcessingMs;
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

    /**
     * reclaim min-idle을 "최악 처리시간 * 1.5" 규칙으로 계산합니다.
     * 계산 입력값이 없으면 기존 reclaim-min-idle 설정값을 사용합니다.
     */
    public long resolveReclaimMinIdleMs() {
        long configuredMinIdleMs = requireReclaimMinIdleMs();
        if (reclaimWorstProcessingMs <= 0L) {
            return configuredMinIdleMs;
        }
        return calculateReclaimMinIdleFromWorstProcessing(reclaimWorstProcessingMs);
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

    /**
     * 최악 처리시간 입력값을 reclaim min-idle 값으로 환산합니다.
     * 계산 규칙은 "worstProcessingMs * 1.5"이며, 정수 ms 단위 유지를 위해 소수점은 올림합니다.
     */
    private long calculateReclaimMinIdleFromWorstProcessing(long worstProcessingMs) {
        // 방어적으로 음수 입력을 0으로 보정합니다.
        long normalizedWorstProcessingMs = Math.max(0L, worstProcessingMs);
        if (normalizedWorstProcessingMs == 0L) {
            return 0L;
        }

        // 곱셈 오버플로를 먼저 차단합니다.
        // (worst * 3 / 2) 계산에서 worst*3이 범위를 넘을 수 있으므로 상한을 직접 검사합니다.
        if (normalizedWorstProcessingMs > Long.MAX_VALUE / RECLAIM_MIN_IDLE_MULTIPLIER_NUMERATOR) {
            return Long.MAX_VALUE;
        }

        // 1.5배 계산을 부동소수점 없이 정수 연산으로 수행합니다.
        long multiplied = normalizedWorstProcessingMs * RECLAIM_MIN_IDLE_MULTIPLIER_NUMERATOR;
        // 소수점 올림(ceil) 처리로 min-idle이 과소 계산되지 않도록 보정한다.
        return (multiplied + RECLAIM_MIN_IDLE_MULTIPLIER_DENOMINATOR - 1L)
                / RECLAIM_MIN_IDLE_MULTIPLIER_DENOMINATOR;
    }

    private TrafficStreamBootstrapException invalidBootstrapConfig(String propertyName, String detail) {
        return new TrafficStreamBootstrapException("Invalid " + propertyName + ": " + detail);
    }

    public enum WorkerRejectionPolicy {
        ABORT,
        CALLER_RUNS
    }
}
