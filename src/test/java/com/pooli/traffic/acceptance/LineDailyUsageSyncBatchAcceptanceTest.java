package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pooli.traffic.service.batch.LineDailyBatchManagerScheduler;

/**
 * 일별 사용량 동기화 배치의 엔드투엔드 인수 테스트입니다.
 *
 * <p>각 시나리오는 어제 날짜를 usageDate로 고정하고, Redis key를 시나리오별로 직접 세팅한 뒤
 * Manager 스케줄러 진입점을 수동으로 호출하여 배치를 구동합니다.
 * 배치 완료 후 LINE_DAILY_BATCH_TARGET 상태와 사용량 DB 레코드를 검증합니다.
 */
class LineDailyUsageSyncBatchAcceptanceTest extends TrafficAcceptanceTestSupport {

    // 배치 전용 fixture 회선 (기존 acceptance 테스트 범위인 1~12와 겹치지 않도록 13~15 사용)
    private static final long LINE_A = 13L; // 시나리오 A: Redis key 없음
    private static final long LINE_B = 14L; // 시나리오 B: 앱 사용량 hash key만 존재
    private static final long LINE_C = 15L; // 시나리오 C: 3종 key 모두 존재

    private static final long FAMILY_A  = 13L;
    private static final long FAMILY_BC = 14L;

    private static final int APP_ID_1 = 1;
    private static final int APP_ID_2 = 2;

    private static final long BATCH_COMPLETION_TIMEOUT_MS = 15_000L;

    private LocalDate usageDate;

    @Autowired
    private LineDailyBatchManagerScheduler lineDailyBatchManagerScheduler;

    @BeforeEach
    void setUpBatchFixture() {
        // 어제 날짜를 배치 동기화 대상일로 고정합니다.
        usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId()).minusDays(1);

        // 이전 테스트의 배치 메타데이터 및 타겟 레코드를 초기화합니다.
        cleanupBatchFixture();

        // 배치 대상 회선 fixture를 DB에 준비합니다.
        upsertBatchTestLine(LINE_A, FAMILY_A);
        upsertBatchTestLine(LINE_B, FAMILY_BC);
        upsertBatchTestLine(LINE_C, FAMILY_BC);

        // 배치 관련 Redis key를 모두 초기화합니다.
        deleteBatchRedisKeys();
    }

    // ─────────────────────────────────────────────────────────────
    // 시나리오 A: Redis key가 단 하나도 없는 회선
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 A: Redis key가 전혀 없는 회선은 target이 SKIPPED 처리된다")
    void scenarioA_noRedisKey_targetSkipped() throws Exception {
        // given: LINE_A에 대한 Redis key를 아무것도 세팅하지 않습니다.

        // when: 배치를 수동으로 구동합니다.
        runBatchAndAwaitCompletion();

        // then: LINE_A의 target row는 SKIPPED 상태여야 합니다.
        assertTargetStatus(LINE_A, "SKIPPED");

        // then: 어떠한 사용량 DB 레코드도 생성되지 않아야 합니다.
        assertNoDailyTotalData(LINE_A);
        assertNoDailyAppTotalData(LINE_A);
        assertNoFamilySharedDailyUsage(LINE_A);

        // then: batch job의 skipped_count가 증가해야 합니다.
        assertBatchJobSkippedCount(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 시나리오 B: 앱 사용량 hash key만 존재하는 회선
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 B: 앱 사용량 key만 있는 회선은 DAILY_APP_TOTAL_DATA만 INSERT된다")
    void scenarioB_onlyAppUsageKey_onlyAppDataInserted() throws Exception {
        // given: LINE_B에 앱 사용량 hash key만 세팅합니다.
        // field 형식: app:{appId}:individual | app:{appId}:shared | app:{appId}:qos
        String appUsageKey = trafficRedisKeyFactory.dailyAppUsageKey(LINE_B, usageDate);
        cacheStringRedisTemplate.opsForHash().putAll(appUsageKey, Map.of(
                "app:" + APP_ID_1 + ":individual", "5000",
                "app:" + APP_ID_1 + ":shared",     "1000",
                "app:" + APP_ID_1 + ":qos",         "500"
        ));

        // when: 배치를 수동으로 구동합니다.
        runBatchAndAwaitCompletion();

        // then: LINE_B의 target row는 DONE 상태여야 합니다.
        assertTargetStatus(LINE_B, "DONE");

        // then: DAILY_APP_TOTAL_DATA에만 레코드가 생성되어야 합니다.
        assertNoDailyTotalData(LINE_B);
        assertDailyAppTotalData(LINE_B, APP_ID_1, 5000L, 1000L, 500L);
        assertNoFamilySharedDailyUsage(LINE_B);

        // then: batch job의 success_count가 증가해야 합니다.
        assertBatchJobSuccessCount(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 시나리오 C: 3종 key 모두 존재하는 회선
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 C: 3종 key 모두 있는 회선은 3개 테이블 모두에 INSERT된다")
    void scenarioC_allKeys_allTablesInserted() throws Exception {
        // given: LINE_C에 3종 Redis key를 모두 세팅합니다.

        // 1. 일별 총 사용량 (String key)
        String totalKey = trafficRedisKeyFactory.dailyTotalUsageKey(LINE_C, usageDate);
        cacheStringRedisTemplate.opsForValue().set(totalKey, "10000");

        // 2. 앱별 사용량 (Hash key) - 복수 앱
        String appUsageKey = trafficRedisKeyFactory.dailyAppUsageKey(LINE_C, usageDate);
        cacheStringRedisTemplate.opsForHash().putAll(appUsageKey, Map.of(
                "app:" + APP_ID_1 + ":individual", "3000",
                "app:" + APP_ID_1 + ":qos",         "500",
                "app:" + APP_ID_2 + ":shared",      "2000"
        ));

        // 3. 공유풀 일별 사용량 (Hash key)
        String sharedKey = trafficRedisKeyFactory.dailySharedUsageKey(LINE_C, usageDate);
        cacheStringRedisTemplate.opsForHash().putAll(sharedKey, Map.of(
                "family_id",    String.valueOf(FAMILY_BC),
                "usage_amount", "8000"
        ));

        // when: 배치를 수동으로 구동합니다.
        runBatchAndAwaitCompletion();

        // then: LINE_C의 target row는 DONE 상태여야 합니다.
        assertTargetStatus(LINE_C, "DONE");

        // then: DAILY_TOTAL_DATA에 총 사용량이 기록되어야 합니다.
        assertDailyTotalData(LINE_C, 10000L);

        // then: DAILY_APP_TOTAL_DATA에 앱별 사용량이 source 단위로 분리되어 기록되어야 합니다.
        assertDailyAppTotalData(LINE_C, APP_ID_1, 3000L, 0L, 500L);
        assertDailyAppTotalData(LINE_C, APP_ID_2, 0L, 2000L, 0L);

        // then: FAMILY_SHARED_USAGE_DAILY에 공유풀 사용량이 기록되어야 합니다.
        assertFamilySharedDailyUsage(FAMILY_BC, LINE_C, 8000L);
    }

    // ─────────────────────────────────────────────────────────────
    // 시나리오 D: 공유풀 hash key가 불완전한 경우 (계약 위반)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 D: 공유풀 hash에 family_id가 누락된 경우 target이 실패 처리된다")
    void scenarioD_incompleteSharedKey_targetFails() throws Exception {
        // given: family_id 필드를 의도적으로 누락합니다.
        String sharedKey = trafficRedisKeyFactory.dailySharedUsageKey(LINE_C, usageDate);
        cacheStringRedisTemplate.opsForHash().put(sharedKey, "usage_amount", "8000");
        // family_id 누락 → LineDailyUsageRedisReader에서 IllegalStateException 발생 예상

        // when: 배치를 수동으로 구동합니다.
        runBatchAndAwaitCompletion();

        // then: target이 RETRYABLE 또는 FAILED 처리되어야 합니다.
        String status = readTargetStatus(LINE_C);
        assertThat(status).isIn("RETRYABLE", "FAILED");

        // then: 어떠한 사용량 DB 레코드도 남으면 안 됩니다 (트랜잭션 롤백).
        assertNoDailyTotalData(LINE_C);
        assertNoDailyAppTotalData(LINE_C);
        assertNoFamilySharedDailyUsage(LINE_C);
    }

    // ─────────────────────────────────────────────────────────────
    // 시나리오 E: 동일 회선 중복 실행 방어 (INSERT IGNORE 검증)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 E: 동기화 완료 후 rerun 시 기존 레코드가 덮어써지지 않는다")
    void scenarioE_rerun_doesNotOverwriteExistingRecord() throws Exception {
        // given: 시나리오 C와 동일하게 3종 key를 세팅하고 1차 배치를 완료합니다.
        String totalKey = trafficRedisKeyFactory.dailyTotalUsageKey(LINE_C, usageDate);
        cacheStringRedisTemplate.opsForValue().set(totalKey, "10000");

        runBatchAndAwaitCompletion();
        assertDailyTotalData(LINE_C, 10000L);

        // given: Redis의 값을 다른 값으로 바꾼 뒤 배치를 재실행합니다.
        cacheStringRedisTemplate.opsForValue().set(totalKey, "99999");
        cleanupBatchJobAndTargetOnly();

        // when: 배치를 다시 구동합니다.
        runBatchAndAwaitCompletion();

        // then: INSERT IGNORE로 인해 기존 레코드가 그대로 보존되어야 합니다.
        assertDailyTotalData(LINE_C, 10000L); // 99999가 아닌 원본 값 유지

        // then: DAILY_TOTAL_DATA 레코드가 2개로 중복되지 않아야 합니다.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DAILY_TOTAL_DATA WHERE line_id = ? AND usage_date = ?",
                Integer.class, LINE_C, usageDate
        );
        assertThat(count).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────
    // private 헬퍼 메서드
    // ─────────────────────────────────────────────────────────────

    /**
     * 배치 Manager 스케줄러를 수동으로 구동하고, 배치 job이 COMPLETED 상태가 될 때까지 폴링 대기합니다.
     */
    private void runBatchAndAwaitCompletion() throws Exception {
        lineDailyBatchManagerScheduler.runDailyBatchManagerSchedule();

        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() - startedAt < BATCH_COMPLETION_TIMEOUT_MS) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM LINE_DAILY_BATCH_JOB WHERE usage_date = ? AND status = 'COMPLETED'",
                    Integer.class, usageDate
            );
            if (count != null && count > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("Timeout: 배치가 COMPLETED 상태로 전환되지 않았습니다. usageDate=" + usageDate);
    }

    private void assertTargetStatus(long lineId, String expectedStatus) {
        String status = readTargetStatus(lineId);
        assertThat(status)
                .as("LINE %d의 target status가 %s여야 합니다".formatted(lineId, expectedStatus))
                .isEqualTo(expectedStatus);
    }

    private String readTargetStatus(long lineId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM LINE_DAILY_BATCH_TARGET WHERE line_id = ? AND usage_date = ?",
                String.class, lineId, usageDate
        );
    }

    private void assertBatchJobSuccessCount(int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT success_count FROM LINE_DAILY_BATCH_JOB WHERE usage_date = ? AND status = 'COMPLETED'",
                Integer.class, usageDate
        );
        assertThat(count).isGreaterThanOrEqualTo(expectedCount);
    }

    private void assertBatchJobSkippedCount(int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT skipped_count FROM LINE_DAILY_BATCH_JOB WHERE usage_date = ? AND status = 'COMPLETED'",
                Integer.class, usageDate
        );
        assertThat(count).isGreaterThanOrEqualTo(expectedCount);
    }

    private void assertDailyTotalData(long lineId, long expectedTotalUsage) {
        Long actual = jdbcTemplate.queryForObject(
                "SELECT total_usage_data FROM DAILY_TOTAL_DATA WHERE line_id = ? AND usage_date = ?",
                Long.class, lineId, usageDate
        );
        assertThat(actual).isEqualTo(expectedTotalUsage);
    }

    private void assertNoDailyTotalData(long lineId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DAILY_TOTAL_DATA WHERE line_id = ? AND usage_date = ?",
                Integer.class, lineId, usageDate
        );
        assertThat(count).isZero();
    }

    private void assertDailyAppTotalData(long lineId, int appId,
                                         long expectedIndividual, long expectedShared, long expectedQos) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT individual_usage_data, shared_usage_data, qos_usage_data
                FROM DAILY_APP_TOTAL_DATA
                WHERE line_id = ? AND application_id = ? AND usage_date = ?
                """,
                lineId, appId, usageDate
        );
        assertThat((Long) row.get("individual_usage_data")).isEqualTo(expectedIndividual);
        assertThat((Long) row.get("shared_usage_data")).isEqualTo(expectedShared);
        assertThat((Long) row.get("qos_usage_data")).isEqualTo(expectedQos);
    }

    private void assertNoDailyAppTotalData(long lineId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DAILY_APP_TOTAL_DATA WHERE line_id = ? AND usage_date = ?",
                Integer.class, lineId, usageDate
        );
        assertThat(count).isZero();
    }

    private void assertFamilySharedDailyUsage(long familyId, long lineId, long expectedUsageAmount) {
        Long actual = jdbcTemplate.queryForObject(
                """
                SELECT usage_amount FROM FAMILY_SHARED_USAGE_DAILY
                WHERE family_id = ? AND line_id = ? AND usage_date = ?
                """,
                Long.class, familyId, lineId, usageDate
        );
        assertThat(actual).isEqualTo(expectedUsageAmount);
    }

    private void assertNoFamilySharedDailyUsage(long lineId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM FAMILY_SHARED_USAGE_DAILY WHERE line_id = ? AND usage_date = ?",
                Integer.class, lineId, usageDate
        );
        assertThat(count).isZero();
    }

    private void upsertBatchTestLine(long lineId, long familyId) {
        jdbcTemplate.update("""
                INSERT INTO LINE (
                    line_id, user_id, plan_id, phone, block_end_at, total_data,
                    last_balance_refreshed_at, is_main, individual_threshold,
                    is_threshold_active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, 200,
                    STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'),
                    0, 0, 0, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE
                    deleted_at = NULL, updated_at = NOW(6)
                """,
                lineId, fixtureIds.userId(), fixtureIds.planId(),
                "010-8000-%04d".formatted(lineId)
        );
        jdbcTemplate.update("""
                INSERT INTO FAMILY (
                    family_id, pool_base_data, pool_total_data, family_threshold,
                    is_threshold_active, last_balance_refreshed_at, created_at, updated_at
                ) VALUES (?, 0, 100, 0, 0,
                    STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'),
                    NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE deleted_at = NULL, updated_at = NOW(6)
                """, familyId
        );
        jdbcTemplate.update("""
                INSERT INTO FAMILY_LINE (family_id, line_id, role, is_public, created_at, updated_at)
                VALUES (?, ?, 'MEMBER', 1, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE updated_at = NOW(6)
                """, familyId, lineId
        );
    }

    private void cleanupBatchFixture() {
        cleanupBatchJobAndTargetOnly();
        jdbcTemplate.update(
                "DELETE FROM DAILY_APP_TOTAL_DATA WHERE line_id IN (?,?,?) AND usage_date = ?",
                LINE_A, LINE_B, LINE_C, usageDate
        );
        jdbcTemplate.update(
                "DELETE FROM DAILY_TOTAL_DATA WHERE line_id IN (?,?,?) AND usage_date = ?",
                LINE_A, LINE_B, LINE_C, usageDate
        );
        jdbcTemplate.update(
                "DELETE FROM FAMILY_SHARED_USAGE_DAILY WHERE line_id IN (?,?,?) AND usage_date = ?",
                LINE_A, LINE_B, LINE_C, usageDate
        );
    }

    private void cleanupBatchJobAndTargetOnly() {
        jdbcTemplate.update(
                "DELETE FROM LINE_DAILY_BATCH_TARGET WHERE line_id IN (?,?,?) AND usage_date = ?",
                LINE_A, LINE_B, LINE_C, usageDate
        );
        jdbcTemplate.update(
                "DELETE FROM LINE_DAILY_BATCH_JOB WHERE usage_date = ?", usageDate
        );
    }

    private void deleteBatchRedisKeys() {
        for (long lineId : new long[]{LINE_A, LINE_B, LINE_C}) {
            cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate));
            cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate));
            cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate));
        }
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.lineDailyBatchManagerLockKey());
    }
}
