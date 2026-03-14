package com.pooli.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
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

@Configuration
@Profile({"default", "local", "api", "traffic"})
@EnableConfigurationProperties({StreamsRedisProperties.class, AppStreamsProperties.class, AppRedisProperties.class})
public class StreamsRedisConfig {

    @Bean("streamsRedisConnectionFactory")
    public RedisConnectionFactory streamsRedisConnectionFactory(StreamsRedisProperties properties) {
        StreamsRedisProperties.SentinelProperties sentinel = properties.getSentinel();

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

    @Bean("streamsStringRedisTemplate")
    public StringRedisTemplate streamsStringRedisTemplate(
            @Qualifier("streamsRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    private boolean isSentinelConfigured(String sentinelMaster, String sentinelNodes) {
        boolean hasMaster = StringUtils.hasText(sentinelMaster);
        boolean hasNodes = StringUtils.hasText(sentinelNodes);

        if (hasMaster != hasNodes) {
            throw new IllegalStateException(
                    "app.redis.streams.sentinel.master and app.redis.streams.sentinel.nodes must be configured together."
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
            throw new IllegalStateException("app.redis.streams.sentinel.nodes must include at least one host:port.");
        }

        return nodes;
    }

    private RedisNode parseSentinelNode(String rawNode) {
        String[] hostPort = rawNode.split(":", 2);
        if (hostPort.length != 2 || !StringUtils.hasText(hostPort[0]) || !StringUtils.hasText(hostPort[1])) {
            throw new IllegalArgumentException("Invalid streams redis sentinel node format: " + rawNode);
        }

        try {
            int port = Integer.parseInt(hostPort[1].trim());
            return new RedisNode(hostPort[0].trim(), port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid streams redis sentinel node port: " + rawNode, e);
        }
    }
}
