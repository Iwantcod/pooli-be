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
    private SentinelProperties sentinel = new SentinelProperties();

    @Getter
    @Setter
    public static class SentinelProperties {
        private String master;
        private String nodes;
        private String password;
    }
}

