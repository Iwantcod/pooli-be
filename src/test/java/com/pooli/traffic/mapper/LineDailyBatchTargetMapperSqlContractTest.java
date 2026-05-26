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

/**
 * target table migration과 mapper XML의 핵심 SQL 계약을 문자열로 고정한다.
 * 실제 DB 없이 unique key, worker claim 인덱스, usage_date 기준 조회 정책을 빠르게 검증한다.
 */
class LineDailyBatchTargetMapperSqlContractTest {

    private static final String MAPPER_XML = "src/main/resources/mapper/traffic/LineDailyBatchTargetMapper.xml";
    private static final String MIGRATION_SQL =
            "src/main/resources/db/migration/V2605250410__create_line_daily_batch_target.sql";

    @Test
    @DisplayName("LINE_DAILY_BATCH_TARGET migration은 usage_date와 line_id unique key를 만든다")
    void migrationCreatesUsageDateLineIdUniqueKey() {
        String sql = migrationSql();

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS LINE_DAILY_BATCH_TARGET"));
        assertTrue(sql.contains("PRIMARY KEY (id)"));
        assertTrue(sql.contains("UNIQUE KEY uk_line_daily_batch_target (usage_date, line_id)"));
    }

    @Test
    @DisplayName("LINE_DAILY_BATCH_TARGET migration은 batch metadata id 컬럼을 만들지 않는다")
    void migrationDoesNotCreateBatchMetadataId() {
        String sql = migrationSql();

        assertFalse(sql.toLowerCase().contains("batch_job_id"));
    }

    @Test
    @DisplayName("LINE_DAILY_BATCH_TARGET migration과 mapper는 updated_at 컬럼을 사용하지 않는다")
    void targetTableDoesNotUseUpdatedAt() {
        String migration = migrationSql();
        String mapper = mapperXml();

        assertFalse(migration.contains("\n    updated_at"));
        assertFalse(mapper.contains("property=\"updatedAt\""));
        assertFalse(mapper.contains("column=\"updated_at\""));
    }

    @Test
    @DisplayName("worker claim 인덱스는 usage_date, status, status_updated_at 순서를 유지한다")
    void migrationCreatesClaimIndex() {
        String sql = migrationSql();

        assertTrue(sql.contains(
                "KEY idx_line_daily_batch_target_claim (usage_date, status, status_updated_at)"
        ));
    }

    @Test
    @DisplayName("target set 조회는 batch metadata id 없이 usage_date 기준으로 수행한다")
    void mapperCountsTargetSetByUsageDate() {
        String sql = mapperXml();

        assertTrue(sql.contains("FROM LINE_DAILY_BATCH_TARGET"));
        assertTrue(sql.contains("WHERE usage_date = #{usageDate}"));
        assertFalse(sql.toLowerCase().contains("batch_job_id"));
    }

    private String mapperXml() {
        return read(MAPPER_XML);
    }

    private String migrationSql() {
        return read(MIGRATION_SQL);
    }

    private String read(String path) {
        try {
            return Files.readString(
                    Path.of(path),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
