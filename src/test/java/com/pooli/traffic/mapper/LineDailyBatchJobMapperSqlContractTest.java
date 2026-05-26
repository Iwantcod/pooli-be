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
 * migration과 mapper XML의 핵심 SQL 계약을 문자열로 고정한다.
 * 실제 DB 없이 unique key 미사용과 생성 전 조회 조건을 빠르게 검증한다.
 */
class LineDailyBatchJobMapperSqlContractTest {

    private static final String MAPPER_XML = "src/main/resources/mapper/traffic/LineDailyBatchJobMapper.xml";
    private static final String MIGRATION_SQL =
            "src/main/resources/db/migration/V2605250400__create_line_daily_batch_job.sql";
    private static final String DROP_UPDATED_AT_MIGRATION_SQL =
            "src/main/resources/db/migration/V2605260100__drop_line_daily_batch_job_updated_at.sql";

    @Test
    @DisplayName("LINE_DAILY_BATCH_JOB migration은 DB unique key를 만들지 않는다")
    void migrationDoesNotCreateUniqueKey() {
        String sql = migrationSql();

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS LINE_DAILY_BATCH_JOB"));
        assertTrue(sql.contains("PRIMARY KEY (id)"));
        assertTrue(sql.contains("idx_line_daily_batch_job_lookup"));
        assertFalse(sql.toUpperCase().contains("UNIQUE"));
    }

    @Test
    @DisplayName("자동 생성 방어 조회는 batch_name과 usage_date 기준 기존 row를 먼저 확인한다")
    void selectLatestChecksExistingBatchBeforeInsert() {
        String sql = mapperXml();

        assertTrue(sql.contains("WHERE batch_name = #{batchName}"));
        assertTrue(sql.contains("AND usage_date = #{usageDate}"));
        assertTrue(sql.contains("ORDER BY id DESC"));
        assertTrue(sql.contains("LIMIT 1"));
    }

    @Test
    @DisplayName("신규 metadata row는 PENDING 초기 상태와 count 값을 insert 받는다")
    void insertCreatesInitialMetadataCounts() {
        String sql = mapperXml();

        assertTrue(sql.contains("INSERT INTO LINE_DAILY_BATCH_JOB"));
        assertTrue(sql.contains("#{status}"));
        assertTrue(sql.contains("#{targetCount}"));
        assertTrue(sql.contains("#{successCount}"));
        assertTrue(sql.contains("#{failedCount}"));
        assertTrue(sql.contains("#{skippedCount}"));
    }

    @Test
    @DisplayName("LINE_DAILY_BATCH_JOB updated_at 컬럼은 후속 migration으로 제거하고 mapper에서 사용하지 않는다")
    void updatedAtIsDroppedAndNotMapped() {
        String mapper = mapperXml();
        String migration = read(DROP_UPDATED_AT_MIGRATION_SQL);

        assertTrue(migration.contains("ALTER TABLE LINE_DAILY_BATCH_JOB"));
        assertTrue(migration.contains("DROP COLUMN updated_at"));
        assertFalse(mapper.contains("property=\"updatedAt\""));
        assertFalse(mapper.contains("column=\"updated_at\""));
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
