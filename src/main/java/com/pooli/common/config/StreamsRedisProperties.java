package com.pooli.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.redis.streams")
public class StreamsRedisProperties {
    private String host;
    private int port;
    private String password;
    /**
     * Redis TCP connect timeout(ms). 0 이하면 드라이버 기본값을 사용합니다.
     */
    private long connectTimeoutMs;
    /**
     * Redis command timeout(ms). 0 이하면 드라이버 기본값을 사용합니다.
     */
    private long commandTimeoutMs;
    private SentinelProperties sentinel = new SentinelProperties();

    @Getter
    @Setter
    public static class SentinelProperties {
        private String master;
        private String nodes;
        private String password;
    }
}
