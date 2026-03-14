package com.pooli.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
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
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.sentinel.master:}") String sentinelMaster,
            @Value("${spring.data.redis.sentinel.nodes:}") String sentinelNodes,
            @Value("${spring.data.redis.sentinel.password:}") String sentinelPassword
    ) {
        if (isSentinelConfigured(sentinelMaster, sentinelNodes)) {
            RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
            configuration.setMaster(sentinelMaster);
            parseSentinelNodes(sentinelNodes)
                    .forEach(node -> configuration.sentinel(node.getHost(), node.getPort()));

            if (StringUtils.hasText(password)) {
                configuration.setPassword(RedisPassword.of(password));
            }
            if (StringUtils.hasText(sentinelPassword)) {
                configuration.setSentinelPassword(RedisPassword.of(sentinelPassword));
            }

            return new LettuceConnectionFactory(configuration);
        }

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);

        if (StringUtils.hasText(password)) {
            configuration.setPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(configuration);
    }

    private boolean isSentinelConfigured(String sentinelMaster, String sentinelNodes) {
        boolean hasMaster = StringUtils.hasText(sentinelMaster);
        boolean hasNodes = StringUtils.hasText(sentinelNodes);

        if (hasMaster != hasNodes) {
            throw new IllegalStateException(
                    "spring.data.redis.sentinel.master and spring.data.redis.sentinel.nodes must be configured together."
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
            throw new IllegalStateException("spring.data.redis.sentinel.nodes must include at least one host:port.");
        }

        return nodes;
    }

    private RedisNode parseSentinelNode(String rawNode) {
        String[] hostPort = rawNode.split(":", 2);
        if (hostPort.length != 2 || !StringUtils.hasText(hostPort[0]) || !StringUtils.hasText(hostPort[1])) {
            throw new IllegalArgumentException("Invalid sentinel node format: " + rawNode);
        }

        try {
            int port = Integer.parseInt(hostPort[1].trim());
            return new RedisNode(hostPort[0].trim(), port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sentinel node port: " + rawNode, e);
        }
    }
}
