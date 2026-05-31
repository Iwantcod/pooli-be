package com.pooli.traffic.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRestoreVerificationMapperSqlContractTest {

    @Test
    @DisplayName("복구 검증 SQL은 daily total usage를 단일 string counter 기준값으로 산출한다")
    void selectsDailyTotalUsageAsSingleStringCounterTarget() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("'DAILY_TOTAL_USAGE' AS key_type");
        assertThat(sql).contains("'__value__' AS field_name");
        assertThat(sql).contains("SUM(individual_usage + shared_usage + qos_usage) AS expected_value");
        assertThat(sql).doesNotContain("'individual' AS field_name,\n            SUM(individual_usage)");
        assertThat(sql).doesNotContain("'shared',\n            SUM(shared_usage)");
        assertThat(sql).doesNotContain("'qos',\n            SUM(qos_usage)");
    }

    @Test
    @DisplayName("복구 검증 SQL은 개인풀 amount와 qos를 함께 산출한다")
    void selectsIndividualAmountAndQosTogether() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("'amount' AS field_name");
        assertThat(sql).contains("'qos'");
        assertThat(sql)
                .contains("CASE WHEN p.qos_speed_limit IS NULL OR p.qos_speed_limit &lt; 0 THEN 0 ELSE p.qos_speed_limit * 125 END");
    }

    @Test
    @DisplayName("복구 검증 SQL은 공유 사용량이 양수일 때만 가족풀 사용량 target을 산출한다")
    void selectsSharedUsageTargetsOnlyWhenSharedUsageIsPositive() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("HAVING SUM(shared_usage) &gt; 0");
        assertThat(sql).contains("'usage_amount'");
        assertThat(sql).contains("'family_id'");
        assertThat(sql).doesNotContain("'DAILY_SHARED_USAGE',\n            line_id,\n            NULL");
        assertThat(sql).doesNotContain("'MONTHLY_SHARED_USAGE',\n            line_id,\n            NULL");
    }
}
