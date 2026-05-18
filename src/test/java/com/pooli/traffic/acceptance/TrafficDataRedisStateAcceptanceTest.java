package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamRecords;

import com.pooli.traffic.domain.TrafficStreamFields;

/**
 * Redis snapshot의 존재 여부, 값 유효성, RDB hydrate source 상태에 따른 차감 흐름을 검증하는 인수테스트입니다.
 *
 * <p>정상 hydrate, Redis 우선순위, 정책 snapshot 복구, malformed 값과 stale 월의 DLQ 종결 계약을 함께 고정합니다.</p>
 */
class TrafficDataRedisStateAcceptanceTest extends TrafficAcceptanceTestSupport {

    /**
     * 개인풀 snapshot이 없을 때 RDB source로 현재 월 Redis 잔량을 만든 뒤 차감하는 hydrate 계약을 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-01] 개인풀 Redis snapshot이 없으면 RDB source로 hydrate한 뒤 차감한다")
    void shouldHydrateIndividualBalanceFromRdbWhenRedisSnapshotIsMissing() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 150L, 0L, 50L, 50L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 공유풀 snapshot이 없고 개인풀이 비어 있을 때 공유풀 hydrate 후 공유 잔량에서 차감하는 경로를 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-02] 공유풀 Redis snapshot이 없으면 RDB source로 hydrate한 뒤 공유풀에서 차감한다")
    void shouldHydrateSharedBalanceFromRdbWhenRedisSnapshotIsMissing() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 0L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 50L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 50L, 50L, 50L, 50L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 개인풀 Redis snapshot이 이미 있으면 RDB source를 다시 반영하지 않고 Redis 값을 authoritative하게 쓰는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-03] 개인풀 Redis snapshot이 있으면 RDB source보다 Redis 값이 우선한다")
    void shouldPreferRedisIndividualBalanceOverRdbSource() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 80L);
        putSharedBalance(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 30L, DEFAULT_SHARED_SOURCE_BYTES, 50L, 50L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 공유풀 Redis snapshot이 이미 있으면 RDB source 대신 Redis 잔량만으로 부분 성공 여부를 결정하는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-04] 공유풀 Redis snapshot이 있으면 RDB source보다 Redis 값이 우선한다")
    void shouldPreferRedisSharedBalanceOverRdbSource() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 0L);
        putSharedBalance(familyId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 30L, 0L, 20L, "PARTIAL_SUCCESS", "NO_BALANCE");
        assertRedisState(lineId, familyId, appId, 0L, 0L, 30L, 30L, 30L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 한 요청 안에서 개인풀 hydrate 후 부족분을 공유풀 hydrate로 이어 처리하는 연쇄 hydrate 경로를 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-05] 개인풀 hydrate 후 공유풀 hydrate가 이어져도 최종 SUCCESS로 수렴한다")
    void shouldHydrateIndividualThenSharedBalanceInOneRequest() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, 30L);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 20L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 80L, 50L, 50L, 20L);
        assertRdbSources(lineId, familyId, 30L, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 차감 Lua가 읽는 정책 snapshot 일부가 비어 있어도 bootstrap 복구 후 차감 결과가 그대로 유지되는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-06] 전역 정책 snapshot 누락은 hydrate 복구 후 차감 결과를 오염시키지 않는다")
    void shouldRecoverMissingGlobalPolicySnapshotWithoutChangingDeductionResult() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 80L);
        putSharedBalance(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        deleteGlobalPolicySnapshotReadByDeductLua();

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 30L, DEFAULT_SHARED_SOURCE_BYTES, 50L, 50L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 개인풀 Redis amount가 숫자가 아니면 차감 side effect 없이 실패를 DLQ로 격리하는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-07] malformed 개인풀 Redis amount는 done log 없이 DLQ로 종결하고 상태를 바꾸지 않는다")
    void shouldRouteMalformedIndividualRedisAmountToDlqWithoutSideEffects() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String individualBalanceKey = currentIndividualBalanceKey(lineId);
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        cacheStringRedisTemplate.opsForHash().putAll(individualBalanceKey, Map.of("amount", "malformed", "qos", "0"));
        putSharedBalance(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: finalStatus=FAILED, lastLuaStatus=ERROR");
        assertThat(cacheStringRedisTemplate.opsForHash().get(individualBalanceKey, "amount")).isEqualTo("malformed");
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
        assertUsageCounters(lineId, appId, 0L, 0L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 공유풀 Redis amount가 허용되지 않는 sentinel이면 잔량과 usage를 바꾸지 않고 DLQ로 종결하는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-08] invalid sentinel 공유풀 Redis amount는 done log 없이 DLQ로 종결하고 상태를 바꾸지 않는다")
    void shouldRouteInvalidSharedRedisAmountToDlqWithoutSideEffects() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 0L);
        putSharedBalance(familyId, -2L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: finalStatus=FAILED, lastLuaStatus=ERROR");
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(0L);
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(-2L);
        assertUsageCounters(lineId, appId, 0L, 0L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * RDB 개인풀 source가 논리 삭제되어 hydrate할 수 없으면 done log 없이 SNAPSHOT_NOT_FOUND로 격리되는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-09] RDB 개인풀 source snapshot이 없으면 done log 없이 SNAPSHOT_NOT_FOUND DLQ로 종결한다")
    void shouldRouteMissingIndividualRdbSnapshotToDlqWithoutDoneLog() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        markLineDeleted(lineId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: SNAPSHOT_NOT_FOUND");
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(0L);
        assertUsageCounters(lineId, appId, 0L, 0L, 0L);
        assertThat(readLineSourceTotalDataIgnoringDeleted(lineId)).isEqualTo(DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 요청 target 월이 RDB source 기준 월보다 과거이면 stale 요청으로 보고 Redis key를 만들지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[REDIS-10] 요청 월이 RDB source 월보다 과거면 done log 없이 STALE_TARGET_MONTH DLQ로 종결한다")
    void shouldRouteStaleTargetMonthToDlqWithoutDoneLog() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        YearMonth previousMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId()).minusMonths(1);
        LocalDate previousDate = previousMonth.atDay(1);
        long previousMonthEnqueuedAt = previousDate
                .atStartOfDay(trafficRedisRuntimePolicy.zoneId())
                .toInstant()
                .toEpochMilli();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueRawTrafficPayload(lineId, familyId, appId, 50L, previousMonthEnqueuedAt);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: STALE_TARGET_MONTH");
        assertThat(readIndividualBalanceAmountForMonth(lineId, previousMonth)).isEqualTo(0L);
        assertUsageCountersForDate(lineId, appId, previousDate, previousMonth, 0L, 0L, 0L);
        assertRdbSources(lineId, familyId, DEFAULT_INDIVIDUAL_SOURCE_BYTES, DEFAULT_SHARED_SOURCE_BYTES);
    }

    /**
     * 정책 snapshot 복구 경로를 만들기 위해 차감 Lua가 참조하는 일부 정책 key만 제거합니다.
     */
    private void deleteGlobalPolicySnapshotReadByDeductLua() {
        cacheStringRedisTemplate.delete(List.of(
                trafficRedisKeyFactory.policyKey(3),
                trafficRedisKeyFactory.policyKey(4),
                trafficRedisKeyFactory.policyKey(5),
                trafficRedisKeyFactory.policyKey(6),
                trafficRedisKeyFactory.policyBootstrapVersionKey()
        ));
    }

    /**
     * 차감 후 Redis 잔량과 usage counter가 기대한 source별 값으로 남았는지 검증합니다.
     */
    private void assertRedisState(
            long lineId,
            long familyId,
            int appId,
            long expectedIndividualAmount,
            long expectedSharedAmount,
            long expectedDailyTotalUsage,
            long expectedDailyAppUsage,
            long expectedMonthlySharedUsage
    ) {
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(expectedIndividualAmount);
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(expectedSharedAmount);
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(expectedDailyTotalUsage);
        assertThat(readDailyAppUsage(lineId, appId)).isEqualTo(expectedDailyAppUsage);
        assertThat(readMonthlySharedUsage(lineId)).isEqualTo(expectedMonthlySharedUsage);
    }

    /**
     * DLQ 경로에서 usage counter가 증가하지 않았는지 확인하기 위한 공통 검증입니다.
     */
    private void assertUsageCounters(
            long lineId,
            int appId,
            long expectedDailyTotalUsage,
            long expectedDailyAppUsage,
            long expectedMonthlySharedUsage
    ) {
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(expectedDailyTotalUsage);
        assertThat(readDailyAppUsage(lineId, appId)).isEqualTo(expectedDailyAppUsage);
        assertThat(readMonthlySharedUsage(lineId)).isEqualTo(expectedMonthlySharedUsage);
    }

    /**
     * 요청 날짜/월이 현재와 다를 때 해당 날짜/월 Redis usage key를 직접 검증합니다.
     */
    private void assertUsageCountersForDate(
            long lineId,
            int appId,
            LocalDate usageDate,
            YearMonth usageMonth,
            long expectedDailyTotalUsage,
            long expectedDailyAppUsage,
            long expectedMonthlySharedUsage
    ) {
        assertThat(readStringCounter(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate)))
                .isEqualTo(expectedDailyTotalUsage);
        assertThat(readHashCounter(trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate), "app:" + appId))
                .isEqualTo(expectedDailyAppUsage);
        assertThat(readStringCounter(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, usageMonth)))
                .isEqualTo(expectedMonthlySharedUsage);
    }

    /**
     * Redis hydrate와 차감이 RDB source 값을 직접 변경하지 않았는지 검증합니다.
     */
    private void assertRdbSources(
            long lineId,
            long familyId,
            long expectedIndividualSourceBytes,
            long expectedSharedSourceBytes
    ) {
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(expectedIndividualSourceBytes);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(expectedSharedSourceBytes);
    }

    /**
     * 현재 월 개인풀 balance key를 반환해 malformed Redis 값 fixture를 직접 주입할 수 있게 합니다.
     */
    private String currentIndividualBalanceKey(long lineId) {
        return trafficRedisKeyFactory.remainingIndivAmountKey(
                lineId,
                YearMonth.now(trafficRedisRuntimePolicy.zoneId())
        );
    }

    /**
     * 특정 월 개인풀 amount를 읽어 stale 요청이 과거 월 key를 만들지 않았는지 검증합니다.
     */
    private long readIndividualBalanceAmountForMonth(long lineId, YearMonth targetMonth) {
        return readHashCounter(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth), "amount");
    }

    /**
     * Redis string counter를 long으로 읽고, 생성되지 않은 counter는 0으로 취급합니다.
     */
    private long readStringCounter(String key) {
        String value = cacheStringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    /**
     * Redis hash field를 long으로 읽고, 생성되지 않은 field는 0으로 취급합니다.
     */
    private long readHashCounter(String key, String field) {
        Object value = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * RDB hydrate source가 없는 시나리오를 만들기 위해 acceptance fixture line을 논리 삭제합니다.
     */
    private void markLineDeleted(long lineId) {
        int updatedRows = jdbcTemplate.update(
                """
                UPDATE LINE
                SET deleted_at = NOW(6),
                    updated_at = NOW(6)
                WHERE line_id = ?
                """,
                lineId
        );
        assertThat(updatedRows).isEqualTo(1);
    }

    /**
     * 논리 삭제된 line도 포함해 RDB source 원본 값이 유지됐는지 확인합니다.
     */
    private long readLineSourceTotalDataIgnoringDeleted(long lineId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT total_data FROM LINE WHERE line_id = ?",
                Long.class,
                lineId
        );
        assertThat(value).isNotNull();
        return value;
    }

    /**
     * API를 우회해 원하는 enqueuedAt을 가진 payload를 stream에 넣어 target 월 검증 시나리오를 만듭니다.
     */
    private String enqueueRawTrafficPayload(
            long lineId,
            long familyId,
            int appId,
            long apiTotalData,
            long enqueuedAt
    ) throws Exception {
        String traceId = "acceptance-" + UUID.randomUUID();
        String payloadJson = """
                {
                  "traceId": "%s",
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": %d,
                  "enqueuedAt": %d
                }
                """.formatted(traceId, lineId, familyId, appId, apiTotalData, enqueuedAt);
        streamsStringRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of(TrafficStreamFields.PAYLOAD, payloadJson))
                        .withStreamKey(appStreamsProperties.getKeyTrafficRequest())
        );
        return traceId;
    }
}
