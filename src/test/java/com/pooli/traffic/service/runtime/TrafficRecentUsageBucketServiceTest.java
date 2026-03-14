package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

@ExtendWith(MockitoExtension.class)
class TrafficRecentUsageBucketServiceTest {

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    @Nested
    class RecordUsageTest {

        @Test
        void recordsIndividualUsageWithIncrAndTtl() {
            TrafficPayloadReqDto payload = payload(100L, 11L, 22L);
            when(trafficRedisKeyFactory.speedBucketIndividualKey(eq(11L), anyLong()))
                    .thenReturn("pooli:speed_bucket:individual:11:1773201600");
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.increment("pooli:speed_bucket:individual:11:1773201600", 60L))
                    .thenReturn(60L);

            trafficRecentUsageBucketService.recordUsage(TrafficPoolType.INDIVIDUAL, payload, 60L);

            verify(valueOperations).increment("pooli:speed_bucket:individual:11:1773201600", 60L);
            verify(cacheStringRedisTemplate).expire(
                    "pooli:speed_bucket:individual:11:1773201600",
                    Duration.ofSeconds(15)
            );
        }

        @Test
        void skipsWhenUsedBytesNonPositive() {
            trafficRecentUsageBucketService.recordUsage(
                    TrafficPoolType.INDIVIDUAL,
                    payload(100L, 11L, 22L),
                    0L
            );

            verifyNoInteractions(cacheStringRedisTemplate);
            verifyNoInteractions(trafficRedisKeyFactory);
        }
    }

    @Nested
    class ResolveRefillPlanTest {

        @Test
        void resolvesRecent10sPlan() {
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(trafficRedisKeyFactory.speedBucketIndividualKey(eq(11L), anyLong())).thenReturn("bucket:any");
            when(valueOperations.multiGet(anyList()))
                    .thenReturn(Arrays.asList("60", "40", "20", null, null, null, null, null, null, null));

            TrafficRefillPlan plan = trafficRecentUsageBucketService.resolveRefillPlan(
                    TrafficPoolType.INDIVIDUAL,
                    payload(100L, 11L, 22L)
            );

            assertNotNull(plan);
            assertEquals(40L, plan.getDelta());
            assertEquals(3, plan.getBucketCount());
            assertEquals(120L, plan.getBucketSum());
            assertEquals(400L, plan.getRefillUnit());
            assertEquals(120L, plan.getThreshold());
            assertEquals("RECENT_10S", plan.getSource());
        }

        @Test
        void resolvesAllBucketsFallbackPlan() {
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(trafficRedisKeyFactory.speedBucketIndividualKey(eq(11L), anyLong())).thenReturn("bucket:any");
            when(trafficRedisKeyFactory.speedBucketIndividualPattern(11L))
                    .thenReturn("pooli:speed_bucket:individual:11:*");
            when(valueOperations.multiGet(anyList()))
                    .thenReturn(
                            Arrays.asList(null, null, null, null, null, null, null, null, null, null),
                            List.of("100", "200")
                    );
            when(cacheStringRedisTemplate.keys("pooli:speed_bucket:individual:11:*"))
                    .thenReturn(Set.of("pooli:speed_bucket:individual:11:1773201599", "pooli:speed_bucket:individual:11:1773201600"));

            TrafficRefillPlan plan = trafficRecentUsageBucketService.resolveRefillPlan(
                    TrafficPoolType.INDIVIDUAL,
                    payload(100L, 11L, 22L)
            );

            assertNotNull(plan);
            assertEquals(150L, plan.getDelta());
            assertEquals(2, plan.getBucketCount());
            assertEquals(300L, plan.getBucketSum());
            assertEquals(1500L, plan.getRefillUnit());
            assertEquals(450L, plan.getThreshold());
            assertEquals("ALL_BUCKETS", plan.getSource());
        }

        @Test
        void resolvesApiTotalDataFallbackWithMinThreshold() {
            when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(trafficRedisKeyFactory.speedBucketIndividualKey(eq(11L), anyLong())).thenReturn("bucket:any");
            when(trafficRedisKeyFactory.speedBucketIndividualPattern(11L))
                    .thenReturn("pooli:speed_bucket:individual:11:*");
            when(valueOperations.multiGet(anyList()))
                    .thenReturn(Arrays.asList(null, null, null, null, null, null, null, null, null, null));
            when(cacheStringRedisTemplate.keys("pooli:speed_bucket:individual:11:*")).thenReturn(Set.of());

            TrafficRefillPlan plan = trafficRecentUsageBucketService.resolveRefillPlan(
                    TrafficPoolType.INDIVIDUAL,
                    payload(0L, 11L, 22L)
            );

            assertNotNull(plan);
            assertEquals(0L, plan.getDelta());
            assertEquals(0, plan.getBucketCount());
            assertEquals(0L, plan.getBucketSum());
            assertEquals(0L, plan.getRefillUnit());
            assertEquals(1L, plan.getThreshold());
            assertEquals("API_TOTAL_DATA", plan.getSource());
        }

        @Test
        void fallsBackWhenSharedOwnerMissing() {
            TrafficRefillPlan plan = trafficRecentUsageBucketService.resolveRefillPlan(
                    TrafficPoolType.SHARED,
                    payload(100L, 11L, null)
            );

            assertNotNull(plan);
            assertEquals("API_TOTAL_DATA", plan.getSource());
            assertEquals(100L, plan.getRefillUnit());
            assertEquals(30L, plan.getThreshold());
            verify(cacheStringRedisTemplate, never()).opsForValue();
        }
    }

    private TrafficPayloadReqDto payload(Long apiTotalData, Long lineId, Long familyId) {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(lineId)
                .familyId(familyId)
                .appId(33)
                .apiTotalData(apiTotalData)
                .enqueuedAt(1_773_201_600_000L)
                .build();
    }
}
