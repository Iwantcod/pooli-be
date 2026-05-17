package com.pooli.monitoring.metrics;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.RedisTarget;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 능동 헬스체크 결과를 Prometheus gauge로 반영합니다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
public class TrafficRedisHealthCheckMetricsCollector {

    private final StringRedisTemplate cacheStringRedisTemplate;
    private final StringRedisTemplate streamsStringRedisTemplate;
    private final TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;

    /**
     * cache Redis와 streams Redis 각각의 template을 주입받아 헬스체크 대상을 분리합니다.
     *
     * <p>1. `cacheStringRedisTemplate`은 정책/차감/잔량 Redis에 PING을 보낼 때 사용합니다.
     * <br>2. `streamsStringRedisTemplate`은 Redis Stream 전용 Redis에 PING을 보낼 때 사용합니다.
     * <br>3. PING 결과는 `TrafficRedisAvailabilityMetrics`를 통해 Prometheus gauge로 반영합니다.
     */
    public TrafficRedisHealthCheckMetricsCollector(
            @Qualifier("cacheStringRedisTemplate") StringRedisTemplate cacheStringRedisTemplate,
            @Qualifier("streamsStringRedisTemplate") StringRedisTemplate streamsStringRedisTemplate,
            TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics
    ) {
        this.cacheStringRedisTemplate = cacheStringRedisTemplate;
        this.streamsStringRedisTemplate = streamsStringRedisTemplate;
        this.trafficRedisAvailabilityMetrics = trafficRedisAvailabilityMetrics;
    }

    /**
     * 설정된 주기마다 두 Redis에 능동 PING을 수행합니다.
     *
     * <p>1. cache Redis 상태를 먼저 확인합니다.
     * <br>2. streams Redis 상태를 이어서 확인합니다.
     * <br>3. 각 Redis의 성공/실패 결과는 독립적인 metric tag로 기록됩니다.
     */
    @Scheduled(fixedDelayString = "${app.traffic.redis-healthcheck.interval-ms:2000}")
    public void collect() {
        ping(RedisTarget.CACHE, cacheStringRedisTemplate);
        ping(RedisTarget.STREAMS, streamsStringRedisTemplate);
    }

    /**
     * 단일 Redis 대상에 PING을 보내고 결과를 metric으로 반영합니다.
     *
     * <p>1. Redis connection callback 안에서 `PING` 명령을 실행합니다.
     * <br>2. 응답이 `PONG`이면 해당 Redis의 `up` gauge를 1로 기록합니다.
     * <br>3. 예외가 발생하거나 `PONG`이 아니면 실패로 기록하고 연속 실패 횟수를 증가시킵니다.
     */
    private void ping(RedisTarget redisTarget, StringRedisTemplate redisTemplate) {
        try {
            // RedisTemplate의 connection callback을 사용해 실제 Redis PING 명령을 직접 실행합니다.
            String response = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            trafficRedisAvailabilityMetrics.recordPingResult(redisTarget, "PONG".equalsIgnoreCase(response));
        } catch (RuntimeException e) {
            trafficRedisAvailabilityMetrics.recordPingResult(redisTarget, false);
            log.debug("traffic_redis_ping_failed redis={}", redisTarget.tagValue(), e);
        }
    }
}
