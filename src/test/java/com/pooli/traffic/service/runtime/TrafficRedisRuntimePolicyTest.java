package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRedisRuntimePolicyTest {

    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy = new TrafficRedisRuntimePolicy();

    @Test
    void returnsAsiaSeoulZoneId() {
        assertEquals(ZoneId.of("Asia/Seoul"), trafficRedisRuntimePolicy.zoneId());
    }

    @Test
    void formatsDateSuffixBySpec() {
        assertEquals("20260311", trafficRedisRuntimePolicy.formatYyyyMmDd(LocalDate.of(2026, 3, 11)));
        assertEquals("202603", trafficRedisRuntimePolicy.formatYyyyMm(YearMonth.of(2026, 3)));
    }

    @Test
    void resolvesExpireAtEpochSecondsBySpec() {
        long dailyExpireAt = trafficRedisRuntimePolicy.resolveDailyExpireAtEpochSeconds(LocalDate.of(2026, 3, 11));
        long monthlyExpireAt = trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(YearMonth.of(2026, 3));

        assertEquals(1_773_269_999L, dailyExpireAt);
        assertEquals(1_775_833_199L, monthlyExpireAt);
    }
}

