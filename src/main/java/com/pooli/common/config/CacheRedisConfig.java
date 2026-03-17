package com.pooli.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * traffic 런타임에서 사용하는 cache Redis 연결과 AOF 초기화 빈을 구성합니다.
 * cache Redis가 정책/사용량/멱등 키의 영속 경계이므로 연결 생성 시점에 AOF 검증도 함께 묶습니다.
 */
@Configuration
@Profile({"local", "api", "traffic"})
@EnableConfigurationProperties(CacheRedisProperties.class)
public class CacheRedisConfig {

    @Bean("cacheRedisConnectionFactory")
    public RedisConnectionFactory cacheRedisConnectionFactory(CacheRedisProperties properties) {
        CacheRedisProperties.SentinelProperties sentinel = properties.getSentinel();

        if (isSentinelConfigured(sentinel.getMaster(), sentinel.getNodes())) {
            RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
            configuration.setMaster(sentinel.getMaster());
            parseSentinelNodes(sentinel.getNodes())
                    .forEach(node -> configuration.sentinel(node.getHost(), node.getPort()));

            if (StringUtils.hasText(properties.getPassword())) {
                configuration.setPassword(RedisPassword.of(properties.getPassword()));
            }
            if (StringUtils.hasText(sentinel.getPassword())) {
                configuration.setSentinelPassword(RedisPassword.of(sentinel.getPassword()));
            }

            return new LettuceConnectionFactory(configuration);
        }

        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());

        if (StringUtils.hasText(properties.getPassword())) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }

        return new LettuceConnectionFactory(configuration);
    }

    @Bean("cacheStringRedisTemplate")
    public StringRedisTemplate cacheStringRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * cache Redis 전용 AOF 설정기입니다.
     * streams/session Redis와 분리된 cache Redis만 대상으로 동작하게 별도 빈으로 둡니다.
     */
    @Bean
    public CacheRedisAofBackupService cacheRedisAofBackupService(
            @Qualifier("cacheStringRedisTemplate") StringRedisTemplate cacheStringRedisTemplate,
            CacheRedisProperties properties
    ) {
        return new CacheRedisAofBackupService(cacheStringRedisTemplate, properties);
    }

    /**
     * 애플리케이션 부팅 직후 AOF 상태를 맞추거나 검증합니다.
     * Redis가 AOF 없이 올라온 상태에서 트래픽 캐시가 쓰이기 시작하는 것을 막기 위한 훅입니다.
     */
    @Bean
    public ApplicationRunner cacheRedisAofInitializer(CacheRedisAofBackupService cacheRedisAofBackupService) {
        return args -> cacheRedisAofBackupService.ensureAofReady();
    }

    private boolean isSentinelConfigured(String sentinelMaster, String sentinelNodes) {
        boolean hasMaster = StringUtils.hasText(sentinelMaster);
        boolean hasNodes = StringUtils.hasText(sentinelNodes);

        if (hasMaster != hasNodes) {
            throw new IllegalStateException(
                    "app.redis.cache.sentinel.master and app.redis.cache.sentinel.nodes must be configured together."
            );
        }
        return hasMaster;
    }

    private List<RedisNode> parseSentinelNodes(String sentinelNodes) {
        List<RedisNode> nodes = Arrays.stream(sentinelNodes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::parseSentinelNode)
                .toList();

        if (nodes.isEmpty()) {
            throw new IllegalStateException("app.redis.cache.sentinel.nodes must include at least one host:port.");
        }

        return nodes;
    }

    private RedisNode parseSentinelNode(String rawNode) {
        String[] hostPort = rawNode.split(":", 2);
        if (hostPort.length != 2 || !StringUtils.hasText(hostPort[0]) || !StringUtils.hasText(hostPort[1])) {
            throw new IllegalArgumentException("Invalid cache redis sentinel node format: " + rawNode);
        }

        try {
            int port = Integer.parseInt(hostPort[1].trim());
            return new RedisNode(hostPort[0].trim(), port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cache redis sentinel node port: " + rawNode, e);
        }
    }
}
