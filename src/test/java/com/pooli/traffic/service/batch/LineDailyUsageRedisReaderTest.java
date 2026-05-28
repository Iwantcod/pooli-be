package com.pooli.traffic.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

@ExtendWith(MockitoExtension.class)
class LineDailyUsageRedisReaderTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);
    private static final long LINE_ID = 11L;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private LineDailyUsageRedisReader lineDailyUsageRedisReader;

    @Test
    @DisplayName("세 Redis key가 모두 없으면 사용량 없음 snapshot을 반환한다")
    void returnsEmptySnapshotWhenAllRedisKeysAreMissing() {
        givenRedisKeys();
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("daily-total-key")).thenReturn(null);
        when(hashOperations.entries("daily-app-key")).thenReturn(Map.of());
        when(hashOperations.entries("daily-shared-key")).thenReturn(Map.of());

        var snapshot = lineDailyUsageRedisReader.read(target());

        assertThat(snapshot.hasAnyUsage()).isFalse();
        assertThat(snapshot.totalUsageData()).isNull();
        assertThat(snapshot.appUsages()).isEmpty();
        assertThat(snapshot.sharedUsage()).isNull();
    }

    @Test
    @DisplayName("일부 Redis key만 있으면 사용량 있음 snapshot을 반환하고 없는 key는 비워 둔다")
    void returnsPartialSnapshotWhenSomeRedisKeysExist() {
        givenRedisKeys();
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("daily-total-key")).thenReturn(null);
        when(hashOperations.entries("daily-app-key")).thenReturn(Map.of(
                "app:101:individual", "10",
                "app:101:qos", "5"
        ));
        when(hashOperations.entries("daily-shared-key")).thenReturn(Map.of());

        var snapshot = lineDailyUsageRedisReader.read(target());

        assertThat(snapshot.hasAnyUsage()).isTrue();
        assertThat(snapshot.totalUsageData()).isNull();
        assertThat(snapshot.appUsages()).containsExactly(
                new DailyAppUsage(101, 10L, 0L, 5L)
        );
        assertThat(snapshot.sharedUsage()).isNull();
    }

    @Test
    @DisplayName("세 Redis key가 모두 있으면 전체 사용량 snapshot을 반환한다")
    void returnsFullSnapshotWhenAllRedisKeysExist() {
        givenRedisKeys();
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("daily-total-key")).thenReturn("30");
        when(hashOperations.entries("daily-app-key")).thenReturn(Map.of(
                "app:101:individual", "10",
                "app:101:shared", "7",
                "app:101:qos", "3"
        ));
        when(hashOperations.entries("daily-shared-key")).thenReturn(Map.of(
                LineDailyUsageRedisReader.DAILY_SHARED_USAGE_AMOUNT_FIELD, "7",
                LineDailyUsageRedisReader.DAILY_SHARED_FAMILY_ID_FIELD, "55"
        ));

        var snapshot = lineDailyUsageRedisReader.read(target());

        assertThat(snapshot.hasAnyUsage()).isTrue();
        assertThat(snapshot.totalUsageData()).isEqualTo(30L);
        assertThat(snapshot.appUsages()).containsExactly(
                new DailyAppUsage(101, 10L, 7L, 3L)
        );
        assertThat(snapshot.sharedUsage())
                .isEqualTo(new DailySharedUsage(55L, 7L));
    }

    @Test
    @DisplayName("일별 공유 사용량 hash에 필수 field가 없으면 실패로 드러낸다")
    void throwsWhenDailySharedUsageRequiredFieldIsMissing() {
        givenRedisKeys();
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("daily-total-key")).thenReturn(null);
        when(hashOperations.entries("daily-app-key")).thenReturn(Map.of());
        when(hashOperations.entries("daily-shared-key")).thenReturn(Map.of(
                LineDailyUsageRedisReader.DAILY_SHARED_USAGE_AMOUNT_FIELD, "7"
        ));

        assertThatThrownBy(() -> lineDailyUsageRedisReader.read(target()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Daily shared usage hash is missing required fields");
    }

    private void givenRedisKeys() {
        when(trafficRedisKeyFactory.dailyTotalUsageKey(LINE_ID, USAGE_DATE)).thenReturn("daily-total-key");
        when(trafficRedisKeyFactory.dailyAppUsageKey(LINE_ID, USAGE_DATE)).thenReturn("daily-app-key");
        when(trafficRedisKeyFactory.dailySharedUsageKey(LINE_ID, USAGE_DATE)).thenReturn("daily-shared-key");
    }

    private LineDailyBatchTarget target() {
        return LineDailyBatchTarget.builder()
                .id(1L)
                .lineId(LINE_ID)
                .usageDate(USAGE_DATE)
                .build();
    }
}
