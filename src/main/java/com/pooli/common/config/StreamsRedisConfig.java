package com.pooli.common.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
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
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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

            return new LettuceConnectionFactory(
                    configuration,
                    buildLettuceClientConfiguration(properties.getConnectTimeoutMs(), properties.getCommandTimeoutMs())
            );
        }

        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());

        if (StringUtils.hasText(properties.getPassword())) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }

        return new LettuceConnectionFactory(
                configuration,
                buildLettuceClientConfiguration(properties.getConnectTimeoutMs(), properties.getCommandTimeoutMs())
        );
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

    /**
     * Streams Redis 연결용 Lettuce 클라이언트 설정을 구성합니다.
     * timeout 값이 0 이하이면 명시 설정을 생략해 Lettuce 기본값을 사용합니다.
     */
    private LettuceClientConfiguration buildLettuceClientConfiguration(
            long connectTimeoutMs,
            long commandTimeoutMs
    ) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
        if (commandTimeoutMs > 0L) {
            // Redis 명령 실행의 최대 대기시간을 지정합니다.
            builder.commandTimeout(Duration.ofMillis(commandTimeoutMs));
        }
        if (connectTimeoutMs > 0L) {
            // Redis 소켓 연결 수립의 최대 대기시간을 지정합니다.
            SocketOptions socketOptions = SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build();
            // 연결 관련 옵션(SocketOptions)을 Lettuce 클라이언트 옵션으로 주입합니다.
            builder.clientOptions(ClientOptions.builder().socketOptions(socketOptions).build());
        }
        // timeout 미지정 시에는 기본값, 지정 시에는 override 값이 적용된 설정을 반환합니다.
        return builder.build();
    }
}
