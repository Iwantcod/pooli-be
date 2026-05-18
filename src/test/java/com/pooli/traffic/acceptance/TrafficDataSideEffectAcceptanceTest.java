package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 차감 결과로 발생해야 하는 outbox, threshold alarm, DLQ 보조 side effect를 검증하는 인수테스트입니다.
 *
 * <p>Redis 차감 자체보다 주변 영속화 결과와 중복 방지 계약을 확인해 운영 후속 작업의 입력을 고정합니다.</p>
 */
class TrafficDataSideEffectAcceptanceTest extends TrafficAcceptanceTestSupport {

    /**
     * 공유풀 잔량이 임계치 이하로 내려가면 알람 로그와 retry 가능한 outbox가 함께 생성되는지 검증합니다.
     */
    @Test
    @DisplayName("[SIDE-01] 공유풀 임계치에 도달하면 threshold log와 outbox가 생성된다")
    void shouldCreateSharedThresholdLogAndOutboxWhenSharedPoolThresholdIsReached() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareSharedDeductionScenario(lineId, familyId, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 60L);

        assertDoneLog(traceId, 0L, 60L, 0L, 0L, "SUCCESS", "OK");
        await("shared threshold outbox", () -> countOutboxRows(traceId, "SHARED_POOL_THRESHOLD_REACHED") == 1);
        assertThat(countThresholdAlarmRows(familyId, 50)).isEqualTo(1);
    }

    /**
     * 같은 acceptance fixture 범위에서 연속 요청을 실행해도 fixture upsert/unique key 충돌 없이 처리되는지 검증합니다.
     */
    @Test
    @DisplayName("[SIDE-03] 같은 fixture 범위에서 연속 요청을 실행해도 unique constraint 충돌 없이 처리된다")
    void shouldProcessRepeatedRequestsWithoutFixtureUniqueConstraintCollision() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 100L);
        putSharedBalance(familyId, 100L);

        String firstTraceId = enqueueTrafficRequest(lineId, familyId, appId, 20L);
        String secondTraceId = enqueueTrafficRequest(lineId, familyId, appId, 20L);

        assertDoneLog(firstTraceId, 20L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertDoneLog(secondTraceId, 20L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(60L);
    }

    /**
     * Redis-only 차감은 RDB의 원천 잔량 컬럼을 직접 감소시키지 않는 side effect 경계를 검증합니다.
     */
    @Test
    @DisplayName("[SIDE-04] Redis 차감 중 RDB 원천 잔량은 직접 감소하지 않는다")
    void shouldKeepRdbSourceBalancesUnchangedDuringRedisDeduction() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, 100L);
        setFamilySourcePoolTotalData(familyId, 100L);
        putIndividualBalance(lineId, 30L);
        putSharedBalance(familyId, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 20L, 0L, 0L, "SUCCESS", "OK");
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(100L);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(100L);
    }

    /**
     * 같은 family/month/threshold 조합의 알람 outbox가 중복 생성되지 않는 월 단위 dedupe 계약을 검증합니다.
     */
    @Test
    @DisplayName("[SIDE-06] 같은 family/month/threshold 알람 outbox는 월 1회만 생성된다")
    void shouldCreateSharedThresholdOutboxOnlyOnceForSameFamilyMonthAndThreshold() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareSharedDeductionScenario(lineId, familyId, 100L);

        String firstTraceId = enqueueTrafficRequest(lineId, familyId, appId, 60L);
        String secondTraceId = enqueueTrafficRequest(lineId, familyId, appId, 1L);

        assertDoneLog(firstTraceId, 0L, 60L, 0L, 0L, "SUCCESS", "OK");
        assertDoneLog(secondTraceId, 0L, 1L, 0L, 0L, "SUCCESS", "OK");
        await("first shared threshold outbox", () -> countOutboxRows(firstTraceId, "SHARED_POOL_THRESHOLD_REACHED") == 1);
        assertThat(countOutboxRows(secondTraceId, "SHARED_POOL_THRESHOLD_REACHED")).isZero();
        assertThat(countThresholdAlarmRows(familyId, 50)).isEqualTo(1);
    }

    /**
     * hydrate 실패 요청은 done log 없이 DLQ로 끝나되 dedupe cleanup outbox는 남기는지 검증합니다.
     */
    @Test
    @DisplayName("[SIDE-08] invalid hydrate result는 done log 없이 DLQ로만 종결된다")
    void shouldTerminateInvalidHydrateResultWithDlqWithoutDoneLog() throws Exception {
        long lineId = LINE_ID_12;
        long familyId = FAMILY_ID_3;
        int appId = fixtureIds.appId();
        markLineDeleted(lineId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: SNAPSHOT_NOT_FOUND");
        assertThat(countOutboxRows(traceId, "DELETE_IN_FLIGHT_DEDUPE_KEY")).isEqualTo(1);
    }

    /**
     * 공유풀 side effect를 검증하기 위해 개인풀을 비우고 공유풀 잔량만 준비합니다.
     */
    private void prepareSharedDeductionScenario(long lineId, long familyId, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 0L);
        putSharedBalance(familyId, sharedAmount);
    }

    /**
     * 특정 traceId와 event type으로 생성된 Redis outbox row 수를 조회합니다.
     */
    private int countOutboxRows(String traceId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM TRAFFIC_REDIS_OUTBOX
                WHERE trace_id = ?
                  AND event_type = ?
                """,
                Integer.class,
                traceId,
                eventType
        );
        assertThat(count).isNotNull();
        return count;
    }

    /**
     * 현재 월 기준 family threshold alarm log가 지정 threshold로 몇 건 생성됐는지 조회합니다.
     */
    private int countThresholdAlarmRows(long familyId, int thresholdPct) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM TRAFFIC_SHARED_THRESHOLD_ALARM_LOG
                WHERE family_id = ?
                  AND target_month = ?
                  AND threshold_pct = ?
                """,
                Integer.class,
                familyId,
                YearMonth.now(trafficRedisRuntimePolicy.zoneId()).toString(),
                thresholdPct
        );
        assertThat(count).isNotNull();
        return count;
    }

    /**
     * invalid hydrate 시나리오를 만들기 위해 acceptance fixture line을 논리 삭제합니다.
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
}
