package com.pooli.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * app.redis.* 공통 설정값을 바인딩합니다.
 * traffic 처리에서 키 네임스페이스를 통일할 때 사용합니다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.redis")
public class AppRedisProperties {
    private String namespace;
}
