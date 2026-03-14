package com.pooli.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.redis.cache")
public class CacheRedisProperties {
    private String host;
    private int port;
    private String password;
}

