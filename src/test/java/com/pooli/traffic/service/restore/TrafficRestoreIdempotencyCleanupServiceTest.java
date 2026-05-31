package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreIdempotencyCleanupServiceTest {

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @InjectMocks
    private TrafficRestoreIdempotencyCleanupService service;

    @Test
    @DisplayName("전체 복구 성공 후 restore idempotency key prefix를 scan cleanup한다")
    void cleansUpRestoreIdempotencyKeys() {
        when(trafficRedisKeyFactory.restoreIdempotencyKeyPattern())
                .thenReturn("pooli:restore:idempotency:*");
        when(cacheStringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(2L);

        long deletedCount = service.cleanupRestoreIdempotencyKeys();

        assertThat(deletedCount).isEqualTo(2L);
    }
}
