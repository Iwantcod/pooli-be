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
    @DisplayName("metric collector 조회는 최신 usage sync batch 한 건을 찾는다")
    void selectLatestByBatchNameFindsLatestUsageSyncBatch() {
        String sql = mapperXml();
        String selectSql = sql.substring(sql.indexOf("<select id=\"selectLatestByBatchName\""));

        assertTrue(selectSql.contains("WHERE batch_name = #{batchName}"));
        assertTrue(selectSql.contains("ORDER BY id DESC"));
        assertTrue(selectSql.contains("LIMIT 1"));
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
    @DisplayName("rerun metadata row는 새 RUNNING usage sync batch로 생성한다")
    void insertRunningRerunUsageSyncBatchCreatesRunningBatch() {
        String sql = mapperXml();
        String insertSql = sql.substring(sql.indexOf("<insert id=\"insertRunningRerunUsageSyncBatch\""));

        assertTrue(insertSql.contains("'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(insertSql.contains("'RUNNING'"));
        assertTrue(insertSql.contains("run_started_at"));
        assertTrue(insertSql.contains("#{targetCount}"));
        assertTrue(insertSql.contains("0,\n            0,\n            0"));
    }

    @Test
    @DisplayName("worker 시작 감지 조회는 usage_date 기준 RUNNING usage sync batch만 찾는다")
    void selectRunningUsageSyncBatchByUsageDateUsesUsageDateAndRunningGuard() {
        String sql = mapperXml();

        assertTrue(sql.contains("<select id=\"selectRunningUsageSyncBatchByUsageDate\""));
        assertTrue(sql.contains("WHERE batch_name = 'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(sql.contains("AND usage_date = #{usageDate}"));
        assertTrue(sql.contains("AND status = 'RUNNING'"));
        assertTrue(sql.contains("ORDER BY id DESC"));
        assertTrue(sql.contains("LIMIT 1"));
    }

    @Test
    @DisplayName("target insert batch 시작은 PENDING row만 RUNNING으로 전환한다")
    void startRunningUsesPendingGuard() {
        String sql = mapperXml();

        assertTrue(sql.contains("<update id=\"updateStatusFromPending\">"));
        assertTrue(sql.contains("SET status = #{status}"));
        assertTrue(sql.contains("run_started_at = COALESCE(run_started_at, CURRENT_TIMESTAMP(6))"));
        assertTrue(sql.contains("manager_instance_id = #{managerInstanceId}"));
        assertTrue(sql.contains("WHERE id = #{id}"));
        assertTrue(sql.contains("AND status = 'PENDING'"));
    }

    @Test
    @DisplayName("target insert batch 완료는 target_count와 success_count를 같은 값으로 확정한다")
    void completeTargetInsertBatchSetsCountsAndCompletedStatus() {
        String sql = mapperXml();

        assertTrue(sql.contains("<update id=\"completeRunningTargetInsertBatch\">"));
        assertTrue(sql.contains("SET status = 'COMPLETED'"));
        assertTrue(sql.contains("target_count = #{targetCount}"));
        assertTrue(sql.contains("success_count = #{targetCount}"));
        assertTrue(sql.contains("failed_count = 0"));
        assertTrue(sql.contains("AND batch_name = 'LINE_DAILY_TARGET_INSERT_BATCH'"));
        assertTrue(sql.contains("AND status = 'RUNNING'"));
    }

    @Test
    @DisplayName("usage sync batch 시작은 target_count를 설정하고 PENDING row만 RUNNING 전환한다")
    void startUsageSyncBatchSetsTargetCountAndUsesPendingGuard() {
        String sql = mapperXml();

        assertTrue(sql.contains("<update id=\"startPendingUsageSyncBatchWithTargetCount\">"));
        assertTrue(sql.contains("SET status = 'RUNNING'"));
        assertTrue(sql.contains("target_count = #{targetCount}"));
        assertTrue(sql.contains("manager_instance_id = #{managerInstanceId}"));
        assertTrue(sql.contains("AND batch_name = 'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(sql.contains("AND status = 'PENDING'"));
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

    @Test
    @DisplayName("usage sync 처리 count 증가는 terminal 상태별 count만 증가시킨다")
    void incrementUsageSyncProcessedCountUsesTerminalStatusCases() {
        String sql = mapperXml();
        String updateSql = sql.substring(sql.indexOf("<update id=\"incrementUsageSyncProcessedCount\""));

        assertTrue(updateSql.contains("success_count = success_count + CASE WHEN #{targetStatus} = 'DONE'"));
        assertTrue(updateSql.contains("skipped_count = skipped_count + CASE WHEN #{targetStatus} = 'SKIPPED'"));
        assertTrue(updateSql.contains("failed_count = failed_count + CASE WHEN #{targetStatus} = 'FAILED'"));
        assertTrue(updateSql.contains("processed_count_updated_at = CURRENT_TIMESTAMP(6)"));
        assertTrue(updateSql.contains("AND batch_name = 'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(updateSql.contains("AND status = 'RUNNING'"));
    }

    @Test
    @DisplayName("bulk usage sync count 증가는 DONE/SKIPPED delta만 한 번에 반영한다")
    void incrementUsageSyncSuccessAndSkippedCountUsesDeltas() {
        String sql = mapperXml();
        String updateSql = sql.substring(sql.indexOf("<update id=\"incrementUsageSyncSuccessAndSkippedCount\""));

        assertTrue(updateSql.contains("success_count = success_count + #{successDelta}"));
        assertTrue(updateSql.contains("skipped_count = skipped_count + #{skippedDelta}"));
        assertTrue(updateSql.contains("processed_count_updated_at = CURRENT_TIMESTAMP(6)"));
        assertTrue(updateSql.contains("WHERE id = #{batchJobId}"));
        assertTrue(updateSql.contains("AND batch_name = 'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(updateSql.contains("AND status = 'RUNNING'"));
        assertFalse(updateSql.contains("failed_count = failed_count +"));
    }

    @Test
    @DisplayName("usage sync 완료 CAS는 terminal count 합계가 target_count와 같을 때만 COMPLETED 전환한다")
    void completeUsageSyncBatchUsesTerminalCountCas() {
        String sql = mapperXml();
        String updateSql = sql.substring(sql.indexOf("<update id=\"completeRunningUsageSyncBatchIfCountsMatch\""));

        assertTrue(updateSql.contains("SET status = 'COMPLETED'"));
        assertTrue(updateSql.contains("finished_at = CURRENT_TIMESTAMP(6)"));
        assertTrue(updateSql.contains("AND batch_name = 'LINE_DAILY_USAGE_SYNC_BATCH'"));
        assertTrue(updateSql.contains("AND status = 'RUNNING'"));
        assertTrue(updateSql.contains("AND target_count = success_count + failed_count + skipped_count"));
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
