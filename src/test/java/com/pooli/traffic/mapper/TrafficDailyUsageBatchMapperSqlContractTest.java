package com.pooli.traffic.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficDailyUsageBatchMapperSqlContractTest {

    private static final String MAPPER_XML = "src/main/resources/mapper/traffic/TrafficDailyUsageBatchMapper.xml";

    @Test
    @DisplayName("일별 총 사용량은 insert-only 계약을 사용한다")
    void dailyTotalUsageUsesInsertOnlyContract() {
        String sql = insertStatement("insertDailyTotalUsage");

        assertTrue(sql.contains("INSERT INTO DAILY_TOTAL_DATA"));
        assertTrue(sql.contains("#{totalUsageData}"));
        assertFalse(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(sql.contains("total_usage_data = total_usage_data +"));
    }

    @Test
    @DisplayName("일별 앱 사용량은 개인/공유/QoS 값을 insert-only 계약으로 저장한다")
    void dailyAppUsageUsesInsertOnlyContract() {
        String sql = insertStatement("insertDailyAppUsage");

        assertTrue(sql.contains("INSERT INTO DAILY_APP_TOTAL_DATA"));
        assertTrue(sql.contains("#{individualUsageData}"));
        assertTrue(sql.contains("#{sharedUsageData}"));
        assertTrue(sql.contains("#{qosUsageData}"));
        assertFalse(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(sql.contains("individual_usage_data = individual_usage_data +"));
        assertFalse(sql.contains("shared_usage_data = shared_usage_data +"));
        assertFalse(sql.contains("qos_usage_data = qos_usage_data +"));
    }

    @Test
    @DisplayName("일별 공유풀 사용량은 insert-only 계약으로 저장하고 contribution_amount를 갱신하지 않는다")
    void familySharedDailyUsageUsesInsertOnlyContract() {
        String sql = insertStatement("insertFamilySharedDailyUsage");

        assertTrue(sql.contains("INSERT INTO FAMILY_SHARED_USAGE_DAILY"));
        assertTrue(sql.contains("#{usageAmount}"));
        assertTrue(sql.contains("contribution_amount"));
        assertFalse(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(sql.contains("contribution_amount ="));
        assertFalse(sql.contains("contribution_amount +"));
    }

    private String insertStatement(String id) {
        String sql = mapperXml();
        int start = sql.indexOf("<insert id=\"" + id + "\"");
        assertTrue(start >= 0);
        int end = sql.indexOf("</insert>", start);
        assertTrue(end >= 0);
        return sql.substring(start, end);
    }

    private String mapperXml() {
        try {
            return Files.readString(
                    Path.of(MAPPER_XML),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
