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
}

