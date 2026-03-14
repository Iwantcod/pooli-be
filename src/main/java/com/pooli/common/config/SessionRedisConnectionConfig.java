package com.pooli.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

@Configuration
@Profile({"default", "local", "api"})
public class SessionRedisConnectionConfig {

    @Bean("springSessionRedisConnectionFactory")
    @Primary
    public RedisConnectionFactory springSessionRedisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password
    ) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);

        if (StringUtils.hasText(password)) {
            configuration.setPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(configuration);
    }
}
