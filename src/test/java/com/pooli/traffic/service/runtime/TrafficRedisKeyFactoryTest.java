package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.common.config.AppRedisProperties;

class TrafficRedisKeyFactoryTest {

    @Test
    void prefixesNamespaceWhenConfigured() {
        TrafficRedisKeyFactory trafficRedisKeyFactory = keyFactoryWithNamespace("pooli");

        assertEquals("pooli:policy:4", trafficRedisKeyFactory.policyKey(4L));
        assertEquals(
                "pooli:remaining_indiv_amount:11:202603",
                trafficRedisKeyFactory.remainingIndivAmountKey(11L, YearMonth.of(2026, 3))
        );
        assertEquals(
                "pooli:daily_total_usage:11:20260311",
                trafficRedisKeyFactory.dailyTotalUsageKey(11L, LocalDate.of(2026, 3, 11))
        );
    }

    @Test
    void returnsRawKeyWhenNamespaceBlank() {
        TrafficRedisKeyFactory trafficRedisKeyFactory = keyFactoryWithNamespace(" ");

        assertEquals("policy:1", trafficRedisKeyFactory.policyKey(1L));
        assertEquals("dedupe:run:trace-001", trafficRedisKeyFactory.dedupeRunKey("trace-001"));
        assertEquals(
                "shared_pool_contribution:metadata:trace-001",
                trafficRedisKeyFactory.sharedPoolContributionMetadataKey("trace-001")
        );
    }

    @Test
    void trimsTraceIdForDedupeKey() {
        TrafficRedisKeyFactory trafficRedisKeyFactory = keyFactoryWithNamespace("pooli");

        assertEquals(
                "pooli:dedupe:run:trace-001",
                trafficRedisKeyFactory.dedupeRunKey("  trace-001  ")
        );
        assertEquals(
                "pooli:shared_pool_contribution:metadata:trace-001",
                trafficRedisKeyFactory.sharedPoolContributionMetadataKey("  trace-001  ")
        );
    }

    @Test
    void throwsWhenTraceIdBlank() {
        TrafficRedisKeyFactory trafficRedisKeyFactory = keyFactoryWithNamespace("pooli");

        assertThrows(IllegalArgumentException.class, () -> trafficRedisKeyFactory.dedupeRunKey("   "));
        assertThrows(
                IllegalArgumentException.class,
                () -> trafficRedisKeyFactory.sharedPoolContributionMetadataKey("   ")
        );
    }

    private TrafficRedisKeyFactory keyFactoryWithNamespace(String namespace) {
        AppRedisProperties appRedisProperties = new AppRedisProperties();
        appRedisProperties.setNamespace(namespace);
        return new TrafficRedisKeyFactory(appRedisProperties, new TrafficRedisRuntimePolicy());
    }
}
