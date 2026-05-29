package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.RecordId;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.restore.RestoreRange;
import com.pooli.traffic.domain.restore.RestoreVerificationResult;
import com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayService;
import com.pooli.traffic.service.restore.TrafficRestoreVerificationService;

class TrafficRestoreBatchAcceptanceTest extends TrafficAcceptanceTestSupport {

    @Autowired
    private TrafficRestoreVerificationService verificationService;

    @Autowired
    private TrafficRestorePhase2ReplayService phase2ReplayService;

    @Test
    @DisplayName("Redis 장애 후 복구 batch는 잔량과 사용량 key를 DB 원천 데이터 기준으로 복구한다")
    void restoresRedisUsageAndBalanceFromDatabaseSources() {
        LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        YearMonth targetMonth = YearMonth.from(usageDate);
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        cleanupDailyAppFixture(lineId, usageDate, appId);
        setLineSourceTotalData(lineId, 1000L);
        setFamilySourcePoolTotalData(familyId, 1000L);
        insertDailyAppUsage(usageDate, lineId, appId, 70L, 20L, 5L);
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate), "individual", "1");
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth), "amount", "1");

        RestoreVerificationResult result = verificationService.verifyAndCorrect(
                usageDate,
                new RestoreRange(usageDate, usageDate.plusDays(1))
        );

        assertThat(result.failedCorrectionCount()).isZero();
        assertThat(result.correctedCount()).isGreaterThan(0L);
        assertThat(readCacheHashLong(
                trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate),
                "individual"
        )).isEqualTo(70L);
        assertThat(readCacheHashLong(
                trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate),
                "shared"
        )).isEqualTo(20L);
        assertThat(readCacheHashLong(
                trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate),
                "qos"
        )).isEqualTo(5L);
        assertThat(readCacheHashLong(
                trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth),
                "amount"
        )).isEqualTo(930L);
    }

    @Test
    @DisplayName("복구 flag 활성화 중에는 신규 stream 생산과 소비가 중단된다")
    void blocksTrafficWhileRestoreFlagIsActive() throws Exception {
        prepareRestorePolicySnapshot(true);
        String requestBody = """
                {
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": 10
                }
                """.formatted(LINE_ID_1, FAMILY_ID_1, fixtureIds.appId());

        mockMvc.perform(
                        post("/api/traffic/requests")
                                .contentType("application/json")
                                .content(requestBody.getBytes(StandardCharsets.UTF_8))
                )
                .andExpect(status().is5xxServerError());

        assertThat(streamRecordCount(appStreamsProperties.getKeyTrafficRequest())).isZero();
    }

    @Test
    @DisplayName("Redis replay 후 MySQL commit 전 중단되어도 재시작 시 중복 차감하지 않는다")
    void doesNotDoubleApplyWhenWorkerDiesAfterRedisReplay() {
        long doneLogId = 9_000_001L;
        LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        YearMonth targetMonth = YearMonth.from(usageDate);
        String idempotencyKey = trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", String.valueOf(doneLogId));
        cacheStringRedisTemplate.opsForValue().set(idempotencyKey, "1");
        String dailyTotalUsageKey = trafficRedisKeyFactory.dailyTotalUsageKey(LINE_ID_1, usageDate);
        cacheStringRedisTemplate.opsForHash().put(dailyTotalUsageKey, "individual", "11");
        insertProcessingDoneLog(doneLogId, usageDate);
        TrafficDeductDoneLog log = TrafficDeductDoneLog.builder()
                .trafficDeductDoneId(doneLogId)
                .lineId(LINE_ID_1)
                .familyId(FAMILY_ID_1)
                .appId(fixtureIds.appId())
                .enqueuedAt(usageDate.atTime(10, 0))
                .deductedIndividualBytes(40L)
                .deductedSharedBytes(0L)
                .deductedQosBytes(0L)
                .restoreStatus("PROCESSING")
                .build();

        phase2ReplayService.replay(log, "acceptance-worker");

        assertThat(readCacheHashLong(dailyTotalUsageKey, "individual")).isEqualTo(11L);
        assertThat(cacheStringRedisTemplate.hasKey(idempotencyKey)).isFalse();
        assertThat(readRestoreStatus(doneLogId)).isEqualTo("DONE");
        assertThat(readCacheHashLong(
                trafficRedisKeyFactory.remainingIndivAmountKey(LINE_ID_1, targetMonth),
                "amount"
        )).isZero();
    }

    private void insertDailyAppUsage(
            LocalDate usageDate,
            long lineId,
            int appId,
            long individualUsage,
            long sharedUsage,
            long qosUsage
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO DAILY_APP_TOTAL_DATA (
                    usage_date,
                    line_id,
                    application_id,
                    individual_usage_data,
                    shared_usage_data,
                    qos_usage_data,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                """,
                usageDate,
                lineId,
                appId,
                individualUsage,
                sharedUsage,
                qosUsage
        );
    }

    private void cleanupDailyAppFixture(long lineId, LocalDate usageDate, int appId) {
        jdbcTemplate.update(
                "DELETE FROM DAILY_APP_TOTAL_DATA WHERE line_id = ? AND usage_date = ? AND application_id = ?",
                lineId,
                usageDate,
                appId
        );
    }

    private void insertProcessingDoneLog(long doneLogId, LocalDate usageDate) {
        jdbcTemplate.update("DELETE FROM TRAFFIC_DEDUCT_DONE WHERE traffic_deduct_done_id = ?", doneLogId);
        jdbcTemplate.update(
                """
                INSERT INTO TRAFFIC_DEDUCT_DONE (
                    traffic_deduct_done_id,
                    trace_id,
                    record_id,
                    line_id,
                    family_id,
                    app_id,
                    enqueued_at,
                    api_total_data,
                    deducted_individual_bytes,
                    deducted_shared_bytes,
                    deducted_qos_bytes,
                    api_remaining_data,
                    final_status,
                    last_lua_status,
                    failure_reason,
                    created_at,
                    started_at,
                    finished_at,
                    latency,
                    restore_status,
                    restore_status_updated_at,
                    restore_retry_count,
                    restore_last_error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 40, 40, 0, 0, 0, 'DONE', 'APPLIED', NULL,
                          NOW(6), NOW(6), NOW(6), 1, 'PROCESSING', NOW(6), 0, NULL)
                """,
                doneLogId,
                "restore-acceptance-" + doneLogId,
                RecordId.autoGenerate().getValue(),
                LINE_ID_1,
                FAMILY_ID_1,
                fixtureIds.appId(),
                LocalDateTime.of(usageDate, java.time.LocalTime.of(10, 0))
        );
    }

    private String readRestoreStatus(long doneLogId) {
        return jdbcTemplate.queryForObject(
                "SELECT restore_status FROM TRAFFIC_DEDUCT_DONE WHERE traffic_deduct_done_id = ?",
                String.class,
                doneLogId
        );
    }

    private long readCacheHashLong(String key, String field) {
        Object value = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }
}
