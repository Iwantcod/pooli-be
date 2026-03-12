package com.pooli.monitoring.metrics;

import java.time.Duration;

import com.pooli.common.config.AppStreamsProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "traffic"})
public class RedisStreamMetrics {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final AppStreamsProperties appStreamsProperties;

    private volatile double streamLength = 0;
    private volatile double pendingMessages = 0;
    private volatile double oldestPendingIdleSeconds = 0;

    public RedisStreamMetrics(
            @Qualifier("streamsStringRedisTemplate") StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            AppStreamsProperties appStreamsProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.appStreamsProperties = appStreamsProperties;
    }

    @PostConstruct
    public void init() {

        Gauge.builder("traffic_stream_length", () -> streamLength)
                .description("Total number of messages in Redis Stream")
                .register(meterRegistry);

        Gauge.builder("traffic_stream_pending_messages", () -> pendingMessages)
                .description("Pending messages not yet acknowledged by consumers")
                .register(meterRegistry);

        Gauge.builder("traffic_stream_oldest_pending_idle_seconds", () -> oldestPendingIdleSeconds)
                .description("Idle time in seconds of the oldest pending message in the consumer group")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 5000)
    public void collectMetrics() {

        try {
            String streamKey = appStreamsProperties.getKeyTrafficRequest();
            String groupName = appStreamsProperties.getGroupTraffic();

            Long length = redisTemplate.opsForStream().size(streamKey);
            if (length != null) {
                streamLength = length;
            }

            StreamInfo.XInfoGroups groups =
                    redisTemplate.opsForStream().groups(streamKey);

            pendingMessages = 0;
            oldestPendingIdleSeconds = 0;

            groups.forEach(group -> {
                if (groupName.equals(group.groupName())) {
                    pendingMessages = group.pendingCount();
                    oldestPendingIdleSeconds = calculateOldestPendingIdleSeconds(
                            streamKey,
                            groupName,
                            group.pendingCount()
                    );
                }
            });

        } catch (Exception e) {
            // 모니터링 실패는 서비스 영향 없도록
        }
    }

    private double calculateOldestPendingIdleSeconds(
            String streamKey,
            String groupName,
            long pendingCount
    ) {
        if (pendingCount <= 0) {
            return 0;
        }

        long metricsPendingScanCount = Math.max(1L, appStreamsProperties.getMetricsPendingScanCount());
        long pendingScanCount = Math.min(pendingCount, metricsPendingScanCount);

        PendingMessages pendingEntries = redisTemplate.opsForStream().pending(
                streamKey,
                groupName,
                Range.unbounded(),
                pendingScanCount
        );

        if (pendingEntries == null || pendingEntries.isEmpty()) {
            return 0;
        }

        long oldestIdleMs = 0;
        for (PendingMessage pendingEntry : pendingEntries) {
            Duration elapsed = pendingEntry.getElapsedTimeSinceLastDelivery();
            long idleMs = elapsed == null ? 0L : elapsed.toMillis();
            oldestIdleMs = Math.max(oldestIdleMs, idleMs);
        }

        return oldestIdleMs / 1000.0;
    }
}
