package com.pooli.monitoring.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisStreamMetrics {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private volatile double streamLength = 0;
    private volatile double pendingMessages = 0;

    private static final String STREAM_KEY = "traffic-request-stream";
    private static final String GROUP_NAME = "traffic-group";

    @PostConstruct
    public void init() {

        Gauge.builder("traffic_stream_length", () -> streamLength)
                .description("Total number of messages in Redis Stream")
                .register(meterRegistry);

        Gauge.builder("traffic_stream_pending_messages", () -> pendingMessages)
                .description("Pending messages not yet acknowledged by consumers")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 5000)
    public void collectMetrics() {

        try {

            Long length = redisTemplate.opsForStream().size(STREAM_KEY);
            if (length != null) {
                streamLength = length;
            }

            StreamInfo.XInfoGroups groups =
                    redisTemplate.opsForStream().groups(STREAM_KEY);

            groups.forEach(group -> {
                if (GROUP_NAME.equals(group.groupName())) {
                    pendingMessages = group.pendingCount();
                }
            });

        } catch (Exception e) {
            // 모니터링 실패는 서비스 영향 없도록
        }
    }
}