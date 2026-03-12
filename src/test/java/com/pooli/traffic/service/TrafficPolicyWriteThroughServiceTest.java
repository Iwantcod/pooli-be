package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.enums.DayOfWeek;

/**
 * 정책 write-through 서비스가 Redis 키에 올바른 값을 즉시 반영하는지 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class TrafficPolicyWriteThroughServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private TrafficPolicyWriteThroughService trafficPolicyWriteThroughService;

    @Nested
    @DisplayName("syncLineLimit 테스트")
    class SyncLineLimitTest {

        @Test
        @DisplayName("활성 상태에 맞춰 daily/shared limit 키를 즉시 갱신")
        void writesDailyAndSharedLimitValues() {
            // given
            when(trafficRedisKeyFactory.dailyTotalLimitKey(101L)).thenReturn("pooli:daily_total_limit:101");
            when(trafficRedisKeyFactory.monthlySharedLimitKey(101L)).thenReturn("pooli:monthly_shared_limit:101");
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);

            // when
            trafficPolicyWriteThroughService.syncLineLimit(101L, 50_000L, true, 90_000L, false);

            // then
            verify(valueOperations).set("pooli:daily_total_limit:101", "50000");
            verify(valueOperations).set("pooli:monthly_shared_limit:101", "-1");
        }
    }

    @Nested
    @DisplayName("syncAppPolicy 테스트")
    class SyncAppPolicyTest {

        @Test
        @DisplayName("비활성 정책이면 app 제한/화이트리스트 키를 제거")
        void removesKeysWhenAppPolicyInactive() {
            // given
            when(trafficRedisKeyFactory.appDataDailyLimitKey(101L)).thenReturn("pooli:app_data_daily_limit:101");
            when(trafficRedisKeyFactory.appSpeedLimitKey(101L)).thenReturn("pooli:app_speed_limit:101");
            when(trafficRedisKeyFactory.appWhitelistKey(101L)).thenReturn("pooli:app_whitelist:101");
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(cacheStringRedisTemplate.opsForSet()).thenReturn(setOperations);

            // when
            trafficPolicyWriteThroughService.syncAppPolicy(101L, 301, false, 1000L, 2000, true);

            // then
            verify(hashOperations).delete("pooli:app_data_daily_limit:101", "limit:301");
            verify(hashOperations).delete("pooli:app_speed_limit:101", "speed:301");
            verify(setOperations).remove("pooli:app_whitelist:101", "301");
        }
    }

    @Nested
    @DisplayName("syncAppPolicySnapshot 테스트")
    class SyncAppPolicySnapshotTest {

        @Test
        @DisplayName("line 단위 app 정책 키를 비우고 활성 정책만 스냅샷으로 적재")
        void rebuildsAppPolicySnapshotWithActivePoliciesOnly() {
            // given
            when(trafficRedisKeyFactory.appDataDailyLimitKey(101L)).thenReturn("pooli:app_data_daily_limit:101");
            when(trafficRedisKeyFactory.appSpeedLimitKey(101L)).thenReturn("pooli:app_speed_limit:101");
            when(trafficRedisKeyFactory.appWhitelistKey(101L)).thenReturn("pooli:app_whitelist:101");
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
            when(cacheStringRedisTemplate.opsForSet()).thenReturn(setOperations);

            AppPolicy activeWhitelisted = AppPolicy.builder()
                    .lineId(101L)
                    .applicationId(301)
                    .dataLimit(1024L)
                    .speedLimit(2048)
                    .isActive(true)
                    .isWhitelist(true)
                    .build();
            AppPolicy inactive = AppPolicy.builder()
                    .lineId(101L)
                    .applicationId(302)
                    .dataLimit(333L)
                    .speedLimit(444)
                    .isActive(false)
                    .isWhitelist(true)
                    .build();

            // when
            trafficPolicyWriteThroughService.syncAppPolicySnapshot(101L, List.of(activeWhitelisted, inactive));

            // then
            verify(cacheStringRedisTemplate).delete(List.of(
                    "pooli:app_data_daily_limit:101",
                    "pooli:app_speed_limit:101",
                    "pooli:app_whitelist:101"
            ));
            verify(hashOperations).putAll("pooli:app_data_daily_limit:101", Map.of("limit:301", "1024"));
            verify(hashOperations).putAll("pooli:app_speed_limit:101", Map.of("speed:301", "2048"));
            verify(setOperations).add("pooli:app_whitelist:101", "301");
        }

        @Test
        @DisplayName("활성 정책이 없으면 3개 app 정책 키 초기화만 수행")
        void clearsAppPolicyKeysWhenNoActivePolicies() {
            // given
            when(trafficRedisKeyFactory.appDataDailyLimitKey(101L)).thenReturn("pooli:app_data_daily_limit:101");
            when(trafficRedisKeyFactory.appSpeedLimitKey(101L)).thenReturn("pooli:app_speed_limit:101");
            when(trafficRedisKeyFactory.appWhitelistKey(101L)).thenReturn("pooli:app_whitelist:101");

            AppPolicy inactive = AppPolicy.builder()
                    .lineId(101L)
                    .applicationId(302)
                    .isActive(false)
                    .build();

            // when
            trafficPolicyWriteThroughService.syncAppPolicySnapshot(101L, List.of(inactive));

            // then
            verify(cacheStringRedisTemplate).delete(List.of(
                    "pooli:app_data_daily_limit:101",
                    "pooli:app_speed_limit:101",
                    "pooli:app_whitelist:101"
            ));
            verify(hashOperations, times(0)).putAll(eq("pooli:app_data_daily_limit:101"), anyMap());
            verify(hashOperations, times(0)).putAll(eq("pooli:app_speed_limit:101"), anyMap());
            verify(setOperations, times(0)).add(eq("pooli:app_whitelist:101"), anyString());
        }
    }

    @Nested
    @DisplayName("syncRepeatBlock 테스트")
    class SyncRepeatBlockTest {

        @Test
        @DisplayName("활성 repeat block 목록을 day hash 규격으로 변환해 저장")
        void writesRepeatBlockHash() {
            // given
            when(trafficRedisKeyFactory.repeatBlockKey(101L)).thenReturn("pooli:repeat_block:101");
            when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);

            RepeatBlockDayResDto day = RepeatBlockDayResDto.builder()
                    .dayOfWeek(DayOfWeek.MON)
                    .startAt(LocalTime.of(1, 0, 0))
                    .endAt(LocalTime.of(2, 0, 0))
                    .build();
            RepeatBlockPolicyResDto repeatBlock = RepeatBlockPolicyResDto.builder()
                    .repeatBlockId(77L)
                    .lineId(101L)
                    .isActive(true)
                    .days(List.of(day))
                    .build();

            // when
            trafficPolicyWriteThroughService.syncRepeatBlock(101L, List.of(repeatBlock));

            // then
            verify(cacheStringRedisTemplate).delete("pooli:repeat_block:101");
            verify(hashOperations).putAll(
                    eq("pooli:repeat_block:101"),
                    argThat(raw -> {
                        if (!(raw instanceof Map<?, ?> map)) {
                            return false;
                        }
                        return map.size() == 1
                                && "3600:7200".equals(map.get("day:1:77"));
                    })
            );
        }
    }

    @Nested
    @DisplayName("재시도 테스트")
    class RetryTest {

        @Test
        @DisplayName("첫 시도 실패 시 재시도 후 성공")
        void retriesAndSucceeds() {
            // given
            when(trafficRedisKeyFactory.policyKey(1001L)).thenReturn("pooli:policy:1001");
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            doThrow(new RuntimeException("redis temporary error"))
                    .doNothing()
                    .when(valueOperations)
                    .set("pooli:policy:1001", "1");

            // when
            trafficPolicyWriteThroughService.syncPolicyActivation(1001L, true);

            // then
            verify(valueOperations, times(2)).set("pooli:policy:1001", "1");
        }

        @Test
        @DisplayName("즉시 차단 시간은 Asia/Seoul epoch second 문자열로 저장")
        void storesImmediateBlockAsEpochSecond() {
            // given
            when(trafficRedisKeyFactory.immediatelyBlockEndKey(101L)).thenReturn("pooli:immediately_block_end:101");
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));

            // when
            trafficPolicyWriteThroughService.syncImmediateBlockEnd(
                    101L,
                    java.time.LocalDateTime.of(2026, 3, 11, 12, 0, 0)
            );

            // then
            verify(valueOperations).set(
                    eq("pooli:immediately_block_end:101"),
                    argThat(value -> value != null && value.matches("\\d+"))
            );
        }
    }
}
