package com.pooli.traffic.service.restore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 최종 성공 후 남은 replay idempotency key를 정리한다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreIdempotencyCleanupService {

    private static final long SCAN_COUNT = 500L;

    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * restore idempotency prefix를 scan 하면서 발견한 key를 삭제한다.
     *
     * <p>Cursor는 Redis SCAN 결과를 순차적으로 읽는 반복자이다. KEYS 명령처럼 전체 keyspace를 한 번에
     * 잠그지 않고 작은 묶음으로 순회할 수 있어, 복구 종료 후 cleanup이 운영 Redis에 주는 부하를 제한한다.
     */
    public long cleanupRestoreIdempotencyKeys() {
        String pattern = trafficRedisKeyFactory.restoreIdempotencyKeyPattern();
        Long deletedCount = cacheStringRedisTemplate.execute((RedisCallback<Long>) connection -> {
            long count = 0L;
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(SCAN_COUNT)
                    .build();
            // Cursor는 Redis server-side SCAN cursor를 감싸므로 try-with-resources로 명시적으로 닫는다.
            // key 삭제만을 위한 조회이므로 String으로 직렬화해서 데이터를 가져올 필요 없다 -> 원시적인 byte[] 타입으로 바로 가져온다.
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    connection.del(cursor.next());
                    count++;
                }
            }
            return count;
        });
        return deletedCount == null ? 0L : deletedCount;
    }
}
