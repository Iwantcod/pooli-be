package com.pooli.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class CacheRedisAofBackupServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private RedisServerCommands redisServerCommands;

    private CacheRedisProperties cacheRedisProperties;
    private CacheRedisAofBackupService cacheRedisAofBackupService;

    @BeforeEach
    void setUp() {
        cacheRedisProperties = new CacheRedisProperties();
        cacheRedisAofBackupService = new CacheRedisAofBackupService(cacheStringRedisTemplate, cacheRedisProperties);
        lenient().when(redisConnection.serverCommands()).thenReturn(redisServerCommands);

        lenient().when(cacheStringRedisTemplate.execute(org.mockito.Mockito.<RedisCallback<Object>>any()))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRedis(redisConnection);
                });
    }

    @Test
    @DisplayName("AOF 백업이 비활성이면 Redis 설정을 건드리지 않는다")
    void ensureAofReadySkipsWhenDisabled() {
        cacheRedisAofBackupService.ensureAofReady();

        verifyNoInteractions(redisConnection, redisServerCommands);
    }

    @Test
    @DisplayName("AOF 백업이 활성화되면 Redis 설정 적용과 검증을 함께 수행한다")
    void ensureAofReadyConfiguresAndVerifies() {
        // given
        CacheRedisProperties.AofProperties aof = cacheRedisProperties.getAof();
        aof.setEnabled(true);
        aof.setTriggerBackgroundRewriteOnStartup(true);

        mockCurrentConfig("appendonly", "yes");
        mockCurrentConfig("appendfsync", "everysec");
        mockCurrentConfig("auto-aof-rewrite-percentage", "100");
        mockCurrentConfig("auto-aof-rewrite-min-size", "67108864");
        mockCurrentConfig("aof-use-rdb-preamble", "yes");

        // when
        cacheRedisAofBackupService.ensureAofReady();

        // then
        verify(redisServerCommands).setConfig("appendonly", "yes");
        verify(redisServerCommands).setConfig("appendfsync", "everysec");
        verify(redisServerCommands).setConfig("auto-aof-rewrite-percentage", "100");
        verify(redisServerCommands).setConfig("auto-aof-rewrite-min-size", "64mb");
        verify(redisServerCommands).setConfig("aof-use-rdb-preamble", "yes");
        verify(redisServerCommands).rewriteConfig();
        verify(redisServerCommands).bgReWriteAof();
    }

    @Test
    @DisplayName("검증 전용 모드에서 appendonly가 꺼져 있으면 즉시 실패한다")
    void ensureAofReadyFailsFastWhenValidationFails() {
        // given
        CacheRedisProperties.AofProperties aof = cacheRedisProperties.getAof();
        aof.setEnabled(true);
        aof.setConfigureOnStartup(false);

        mockCurrentConfig("appendonly", "no");
        mockCurrentConfig("appendfsync", "everysec");
        mockCurrentConfig("auto-aof-rewrite-percentage", "100");
        mockCurrentConfig("auto-aof-rewrite-min-size", "64mb");
        mockCurrentConfig("aof-use-rdb-preamble", "yes");

        // when & then
        assertThrows(IllegalStateException.class, cacheRedisAofBackupService::ensureAofReady);
        verify(redisServerCommands, never()).setConfig("appendonly", "yes");
        verify(redisServerCommands, never()).rewriteConfig();
    }

    @Test
    @DisplayName("fail-fast를 끄면 검증 실패를 예외 대신 경고로 처리한다")
    void ensureAofReadyWarnsWhenFailFastDisabled() {
        // given
        CacheRedisProperties.AofProperties aof = cacheRedisProperties.getAof();
        aof.setEnabled(true);
        aof.setConfigureOnStartup(false);
        aof.setFailFast(false);

        mockCurrentConfig("appendonly", "no");
        mockCurrentConfig("appendfsync", "everysec");
        mockCurrentConfig("auto-aof-rewrite-percentage", "100");
        mockCurrentConfig("auto-aof-rewrite-min-size", "64mb");
        mockCurrentConfig("aof-use-rdb-preamble", "yes");

        // when & then
        assertDoesNotThrow(() -> cacheRedisAofBackupService.ensureAofReady());
        verify(redisServerCommands, never()).setConfig("appendonly", "yes");
        verify(redisServerCommands, never()).rewriteConfig();
    }

    private void mockCurrentConfig(String key, String value) {
        when(redisServerCommands.getConfig(key)).thenReturn(properties(key, value));
    }

    private Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
