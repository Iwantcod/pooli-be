package com.pooli.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@Profile({"default", "local", "api"})
@EnableRedisHttpSession(redisNamespace = "${spring.session.redis.namespace}")
public class SessionConfig {
}
