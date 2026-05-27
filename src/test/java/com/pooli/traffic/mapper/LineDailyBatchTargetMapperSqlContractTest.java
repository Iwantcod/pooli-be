package com.pooli.traffic.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test
    @DisplayName("empty poll 조회는 usage_date 기준 non-terminal target row만 집계한다")
    void mapperCountsNonTerminalTargetsByUsageDate() {
        String sql = mapperXml();
        String selectSql = extractBlock(sql, "<select id=\"countNonTerminalByUsageDate\"", "</select>", "countNonTerminalByUsageDate");

        assertTrue(selectSql.contains("FROM LINE_DAILY_BATCH_TARGET"));
        assertTrue(selectSql.contains("WHERE usage_date = #{usageDate}"));
        assertTrue(selectSql.contains("status IN ('PENDING', 'PROCESSING', 'RETRYABLE')"));
        assertFalse(selectSql.contains("'DONE'"));
        assertFalse(selectSql.contains("'FAILED'"));
        assertFalse(selectSql.contains("'SKIPPED'"));
    }

    @Test
    @DisplayName("rerun target_count 조회는 usage_date와 status 조건을 사용한다")
    void mapperCountsTargetsByUsageDateAndStatus() {
        String sql = mapperXml();
        String selectSql = extractBlock(sql, "<select id=\"countByUsageDateAndStatus\"", "</select>", "countByUsageDateAndStatus");

        assertTrue(selectSql.contains("FROM LINE_DAILY_BATCH_TARGET"));
        assertTrue(selectSql.contains("WHERE usage_date = #{usageDate}"));
        assertTrue(selectSql.contains("AND status = #{status}"));
    }

    @Test
    @DisplayName("target insert 재개 지점은 usage_date 기준 최대 line_id로 조회한다")
    void mapperSelectsMaxLineIdByUsageDateForResumePoint() {
        String sql = mapperXml();

        assertTrue(sql.contains("<select id=\"selectMaxLineIdByUsageDate\""));
        assertTrue(sql.contains("SELECT COALESCE(MAX(line_id), 0)"));
        assertTrue(sql.contains("FROM LINE_DAILY_BATCH_TARGET"));
        assertTrue(sql.contains("WHERE usage_date = #{usageDate}"));
    }

    @Test
    @DisplayName("target row 생성은 LINE PK 순서 chunk 조회와 INSERT IGNORE를 사용한다")
    void mapperInsertsTargetsInChunksWithInsertIgnore() {
        String sql = mapperXml();

        assertTrue(sql.contains("<select id=\"selectActiveLineIdsAfter\""));
        assertTrue(sql.contains("FROM LINE"));
        assertTrue(sql.contains("AND line_id &gt; #{lastLineId}"));
        assertTrue(sql.contains("ORDER BY line_id"));
        assertTrue(sql.contains("LIMIT #{limit}"));
        assertTrue(sql.contains("INSERT IGNORE INTO LINE_DAILY_BATCH_TARGET"));
    }

    @Test
    @DisplayName("INSERT IGNORE target row 생성은 기존 row 처리 상태 컬럼을 갱신하지 않는다")
    void insertIgnoreDoesNotResetExistingTargetStatusColumns() {
        String sql = mapperXml();
        String insertSql = extractBlock(sql, "<insert id=\"insertIgnoreTargetRows\"", "</insert>", "insertIgnoreTargetRows");

        assertTrue(insertSql.contains("'PENDING'"));
        assertFalse(insertSql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(insertSql.contains("worker_id"));
        assertFalse(insertSql.contains("last_error_code"));
        assertFalse(insertSql.contains("last_error_message"));
    }

    @Test
    @DisplayName("worker claim 조회는 PENDING, RETRYABLE, lease timeout PROCESSING row를 SKIP LOCKED로 선점한다")
    void workerClaimSelectsClaimableTargetsWithSkipLocked() {
        String sql = mapperXml();
        String claimSql = extractBlock(sql, "<select id=\"selectClaimableTargetsForUpdate\"", "</select>", "selectClaimableTargetsForUpdate");

        assertTrue(claimSql.contains("WHERE usage_date = #{usageDate}"));
        assertTrue(claimSql.contains("status IN ('PENDING', 'RETRYABLE')"));
        assertTrue(claimSql.contains("status = 'PROCESSING'"));
        assertTrue(claimSql.contains("status_updated_at &lt; DATE_SUB("));
        assertTrue(claimSql.contains("INTERVAL #{processingLeaseTimeoutSeconds} SECOND"));
        assertTrue(claimSql.contains("ORDER BY status_updated_at ASC, id ASC"));
        assertTrue(claimSql.contains("LIMIT #{limit}"));
        assertTrue(claimSql.contains("FOR UPDATE SKIP LOCKED"));
    }

    @Test
    @DisplayName("worker claim 전환은 선점 row만 PROCESSING으로 바꾸고 worker_id를 기록한다")
    void workerClaimUpdateMarksTargetsProcessing() {
        String sql = mapperXml();
        String updateSql = extractBlock(sql, "<update id=\"markTargetsProcessing\"", "</update>", "markTargetsProcessing");

        assertTrue(updateSql.contains("SET status = 'PROCESSING'"));
        assertTrue(updateSql.contains("status_updated_at = CURRENT_TIMESTAMP(6)"));
        assertTrue(updateSql.contains("worker_id = #{workerId}"));
        assertTrue(updateSql.contains("WHERE id IN"));
        assertTrue(updateSql.contains("collection=\"ids\""));
        assertTrue(updateSql.contains("AND status IN ('PENDING', 'RETRYABLE', 'PROCESSING')"));
    }

    @Test
    @DisplayName("terminal 전환은 현재 worker가 PROCESSING으로 선점한 row만 갱신한다")
    void terminalTransitionUsesProcessingAndWorkerGuard() {
        String sql = mapperXml();
        String updateSql = extractBlock(sql, "<update id=\"markTargetTerminalIfProcessing\"", "</update>", "markTargetTerminalIfProcessing");

        assertTrue(updateSql.contains("SET status = #{status}"));
        assertTrue(updateSql.contains("WHERE id = #{id}"));
        assertTrue(updateSql.contains("AND status = 'PROCESSING'"));
        assertTrue(updateSql.contains("AND worker_id = #{workerId}"));
    }

    @Test
    @DisplayName("RETRYABLE 전환은 현재 worker가 PROCESSING으로 보유한 row만 retry_count 한도 미만에서 증가시킨다")
    void retryableTransitionIncrementsRetryCountBelowMaxWithoutFailedTerminalCount() {
        String sql = mapperXml();
        String updateSql = extractBlock(sql, "<update id=\"markTargetRetryableIfProcessing\"", "</update>", "markTargetRetryableIfProcessing");

        assertTrue(updateSql.contains("SET status = 'RETRYABLE'"));
        assertTrue(updateSql.contains("worker_id = NULL"));
        assertTrue(updateSql.contains("retry_count = retry_count + 1"));
        assertTrue(updateSql.contains("last_error_code = #{lastErrorCode}"));
        assertTrue(updateSql.contains("last_error_message = #{lastErrorMessage}"));
        assertTrue(updateSql.contains("AND status = 'PROCESSING'"));
        assertTrue(updateSql.contains("AND worker_id = #{workerId}"));
        assertTrue(updateSql.contains("AND retry_count &lt; #{maxRetryCount}"));
    }

    @Test
    @DisplayName("FAILED 전환은 현재 worker가 PROCESSING으로 보유한 row만 retry_count 증가 없이 닫는다")
    void failedTransitionDoesNotIncrementRetryCount() {
        String sql = mapperXml();
        String updateSql = extractBlock(sql, "<update id=\"markTargetFailedIfProcessing\"", "</update>", "markTargetFailedIfProcessing");

        assertTrue(updateSql.contains("SET status = 'FAILED'"));
        assertTrue(updateSql.contains("last_error_code = #{lastErrorCode}"));
        assertTrue(updateSql.contains("last_error_message = #{lastErrorMessage}"));
        assertTrue(updateSql.contains("AND status = 'PROCESSING'"));
        assertTrue(updateSql.contains("AND worker_id = #{workerId}"));
        assertFalse(updateSql.contains("retry_count = retry_count + 1"));
    }

    @Test
    @DisplayName("rerun은 FAILED target row만 RETRYABLE로 되돌리고 DONE/SKIPPED는 건드리지 않는다")
    void rerunTransitionUpdatesOnlyFailedTargets() {
        String sql = mapperXml();
        String updateSql = extractBlock(sql, "<update id=\"markFailedTargetsRetryableByUsageDate\"", "</update>", "markFailedTargetsRetryableByUsageDate");

        assertTrue(updateSql.contains("SET status = 'RETRYABLE'"));
        assertTrue(updateSql.contains("worker_id = NULL"));
        assertTrue(updateSql.contains("WHERE usage_date = #{usageDate}"));
        assertTrue(updateSql.contains("AND status = 'FAILED'"));
        assertFalse(updateSql.contains("'DONE'"));
        assertFalse(updateSql.contains("'SKIPPED'"));
    }

    private String extractBlock(String sql, String startToken, String endToken, String failMsg) {
        int start = sql.indexOf(startToken);
        if (start == -1) {
            fail(failMsg + " - start tag not found: " + startToken);
        }
        int end = sql.indexOf(endToken, start);
        if (end == -1) {
            fail(failMsg + " - " + endToken + " not found");
        }
        return sql.substring(start, end + endToken.length());
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
