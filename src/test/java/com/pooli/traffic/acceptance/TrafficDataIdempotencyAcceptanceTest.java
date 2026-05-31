package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.test.context.TestPropertySource;

import com.pooli.traffic.domain.TrafficStreamFields;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * traceId 기반 dedupe 상태와 done log 존재 여부가 NEW/RECLAIM record 처리에 미치는 영향을 검증합니다.
 *
 * <p>이미 처리된 바이트의 remaining 계산, 중복 done log 흡수, reclaim retry 한계, invariant 위반 DLQ 경로를 다룹니다.</p>
 */
@TestPropertySource(properties = {
        "app.streams.key-traffic-request=traffic:deduct:request:acceptance:idempotency",
        "app.streams.group-traffic=traffic-deduct-acceptance-idempotency-cg",
        "app.streams.consumer-name=traffic-deduct-acceptance-idempotency-consumer",
        "app.streams.key-traffic-dlq=traffic:deduct:dlq:acceptance:idempotency",
        "app.streams.reclaim-interval-ms=100",
        "app.streams.reclaim-min-idle-ms=0"
})
class TrafficDataIdempotencyAcceptanceTest extends TrafficAcceptanceTestSupport {

    private final Set<String> traceIdsToCleanup = new LinkedHashSet<>();

    /**
     * 각 테스트가 직접 만든 dedupe run key를 제거해 traceId 재사용이나 retry 상태 누수를 막습니다.
     */
    @AfterEach
    void cleanupIdempotencyFixture() {
        for (String traceId : traceIdsToCleanup) {
            cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dedupeRunKey(traceId));
        }
    }

    /**
     * dedupe에 일부 처리량이 있으면 남은 바이트만 추가 차감하고 done log에는 누적 처리량을 남기는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-01] 기존 dedupe 처리량이 있으면 remaining만 추가 처리하고 done log는 누적 처리량을 기록한다")
    void shouldProcessOnlyRemainingBytesWhenDedupeHasProcessedData() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        putDedupeState(traceId, 30L, 0L, 0L, 0);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 80L, 100L, 20L, 20L, 0L);
        assertDoneLogCount(traceId, 1);
    }

    /**
     * dedupe 누적 처리량이 요청량에 이미 도달한 record는 추가 side effect 없이 완료로 흡수되는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-02] dedupe 누적 처리량이 api_total_data와 같으면 추가 차감 없이 완료된다")
    void shouldNoopWhenDedupeProcessedDataAlreadyReachedApiTotalData() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        putDedupeState(traceId, 50L, 0L, 0L, 0);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
        assertDoneLogCount(traceId, 1);
    }

    /**
     * 이전 partial 처리 이후 재처리될 때 남은 요청량만 공유풀에서 처리해 최종 성공으로 수렴하는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-03] partial 후 재처리에서는 남은 요청량만 공유풀에서 처리해 누적 성공으로 수렴한다")
    void shouldProcessRemainingSharedBytesAfterPartialDedupeState() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 0L, 100L);
        putDedupeState(traceId, 20L, 0L, 0L, 0);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 20L, 30L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 70L, 30L, 30L, 30L);
        assertDoneLogCount(traceId, 1);
    }

    /**
     * dedupe 누적 처리량이 요청량보다 큰 비정상 상태는 불변식 위반으로 보고 차감 없이 DLQ로 보내는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-04] dedupe 누적 처리량이 api_total_data를 넘으면 done log 없이 DLQ로 종결한다")
    void shouldRouteOverflowedDedupeStateToDlqWithoutDoneLog() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        putDedupeState(traceId, 60L, 0L, 0L, 0);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecordContaining(traceId);
        assertThat(dlq.get("reason")).contains("누적 차감량 불변식 위반");
        assertDoneLogCount(traceId, 0);
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
    }

    /**
     * NEW record라도 같은 traceId의 done log가 이미 있으면 재차감하지 않고 기존 완료 결과로 흡수하는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-05] 이미 done log가 있는 NEW trace는 추가 차감 없이 흡수된다")
    void shouldAbsorbNewRecordWhenDoneLogAlreadyExists() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        enqueueRawTrafficPayload(traceId, lineId, familyId, appId, 50L);
        TrafficDeductDoneLog firstDoneLog = assertDoneLog(
                traceId,
                50L,
                0L,
                0L,
                0L,
                "SUCCESS",
                "OK"
        );
        await("dedupe delete outbox is recorded", () -> countDedupeDeleteOutboxRows(traceId) >= 1);
        long individualBalanceAfterFirstRun = readIndividualBalanceAmount(lineId);
        long dailyUsageAfterFirstRun = readDailyTotalUsage(lineId);

        enqueueRawTrafficPayload(traceId, lineId, familyId, appId, 50L);
        await("duplicate dedupe delete outbox is recorded", () -> countDedupeDeleteOutboxRows(traceId) >= 2);

        assertDoneLogCount(traceId, 1);
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(individualBalanceAfterFirstRun);
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(dailyUsageAfterFirstRun);
        assertThat(findRecordId(traceId)).isEqualTo(firstDoneLog.getRecordId());
        assertThat(countDedupeDeleteOutboxRows(traceId)).isGreaterThanOrEqualTo(2);
    }

    /**
     * RECLAIM record에서 done log가 이미 발견되면 orchestration을 다시 수행하지 않고 ACK side effect만 남기는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-06] reclaim 경로에서 이미 done log가 있으면 추가 orchestration 없이 ACK된다")
    void shouldAbsorbReclaimedRecordWhenDoneLogAlreadyExists() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        enqueueRawTrafficPayload(traceId, lineId, familyId, appId, 50L);
        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        await("dedupe delete outbox is recorded", () -> countDedupeDeleteOutboxRows(traceId) >= 1);
        long individualBalanceAfterFirstRun = readIndividualBalanceAmount(lineId);
        long dailyUsageAfterFirstRun = readDailyTotalUsage(lineId);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);
        await("reclaimed duplicate dedupe delete outbox is recorded", () -> countDedupeDeleteOutboxRows(traceId) >= 2);

        assertDoneLogCount(traceId, 1);
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(individualBalanceAfterFirstRun);
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(dailyUsageAfterFirstRun);
        assertThat(countDedupeDeleteOutboxRows(traceId)).isGreaterThanOrEqualTo(2);
    }

    /**
     * reclaim retry count가 한계를 넘은 record는 추가 차감 없이 DLQ로 종결되는지 검증합니다.
     */
    @Test
    @DisplayName("[IDEM-07] reclaim retry가 한계를 넘으면 done log 없이 DLQ로 종결하고 차감하지 않는다")
    void shouldRouteReclaimRetryExceededToDlqWithoutDoneLog() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareIdempotencyScenario(lineId, familyId, 100L, 100L);
        putDedupeState(traceId, 0L, 0L, 0L, 5);

        enqueueReclaimTrafficPayload(traceId, lineId, familyId, appId, 50L);

        Map<String, String> dlq = awaitDlqRecordContaining(traceId);
        assertThat(dlq.get("reason")).contains("reclaim retry exceeded");
        assertDoneLogCount(traceId, 0);
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
    }

    /**
     * 멱등성 테스트가 사용할 RDB source와 Redis balance snapshot을 준비합니다.
     */
    private void prepareIdempotencyScenario(long lineId, long familyId, long individualAmount, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, individualAmount);
        putSharedBalance(familyId, sharedAmount);
    }

    /**
     * acceptance 전용 traceId를 만들고 테스트 후 dedupe key 정리 대상으로 등록합니다.
     */
    private String newTraceId() {
        String traceId = "acceptance-" + UUID.randomUUID();
        traceIdsToCleanup.add(traceId);
        return traceId;
    }

    /**
     * 재처리 시나리오를 만들기 위해 dedupe run hash에 누적 처리량과 retry count를 직접 기록합니다.
     */
    private void putDedupeState(
            String traceId,
            long processedIndividualData,
            long processedSharedData,
            long processedQosData,
            int retryCount
    ) {
        traceIdsToCleanup.add(traceId);
        cacheStringRedisTemplate.opsForHash().putAll(
                trafficRedisKeyFactory.dedupeRunKey(traceId),
                Map.of(
                        "processed_individual_data", String.valueOf(processedIndividualData),
                        "processed_shared_data", String.valueOf(processedSharedData),
                        "processed_qos_data", String.valueOf(processedQosData),
                        "retry_count", String.valueOf(retryCount)
                )
        );
    }

    /**
     * 멱등 처리 후 Redis 잔량과 usage counter가 기대값으로 남았는지 검증합니다.
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
     * stream consumer를 통해 처리될 raw payload를 실제 request stream에 적재합니다.
     */
    private RecordId enqueueRawTrafficPayload(
            String traceId,
            long lineId,
            long familyId,
            int appId,
            long apiTotalData
    ) {
        return streamsStringRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of(TrafficStreamFields.PAYLOAD, payloadJson(traceId, lineId, familyId, appId, apiTotalData)))
                        .withStreamKey(appStreamsProperties.getKeyTrafficRequest())
        );
    }

    /**
     * 실제 request stream record를 consumer group pending으로 남긴 뒤 runner reclaim loop가 처리하게 합니다.
     */
    private void enqueueReclaimTrafficPayload(
            String traceId,
            long lineId,
            long familyId,
            int appId,
            long apiTotalData
    ) {
        boolean wasRunning = trafficStreamConsumerRunner.isRunning();
        if (wasRunning) {
            trafficStreamConsumerRunner.stop();
        }

        try {
            trafficStreamInfraService.ensureConsumerGroup();
            RecordId recordId = enqueueRawTrafficPayload(traceId, lineId, familyId, appId, apiTotalData);
            List<MapRecord<String, String, String>> pendingRecords = trafficStreamInfraService.readBlocking(1);
            assertThat(pendingRecords)
                    .extracting(record -> record.getId().getValue())
                    .contains(recordId.getValue());
        } finally {
            trafficStreamConsumerRunner.start();
        }
    }

    /**
     * 직접 생성 record와 stream 적재에 공통으로 쓰는 트래픽 payload JSON을 구성합니다.
     */
    private String payloadJson(String traceId, long lineId, long familyId, int appId, long apiTotalData) {
        return """
                {
                  "traceId": "%s",
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": %d,
                  "enqueuedAt": %d
                }
                """.formatted(traceId, lineId, familyId, appId, apiTotalData, System.currentTimeMillis());
    }

    /**
     * 여러 DLQ record 중 현재 traceId payload를 포함한 record가 나타날 때까지 기다립니다.
     */
    private Map<String, String> awaitDlqRecordContaining(String traceId) throws Exception {
        long startedAt = System.currentTimeMillis();
        long timeoutMs = 7_000L;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            var records = streamsStringRedisTemplate.opsForStream()
                    .range(appStreamsProperties.getKeyTrafficDlq(), Range.unbounded());
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    Map<String, String> value = record.getValue().entrySet().stream()
                            .collect(Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()),
                                    entry -> String.valueOf(entry.getValue())
                            ));
                    if (value.getOrDefault(TrafficStreamFields.PAYLOAD, "").contains(traceId)) {
                        return value;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timeout while waiting DLQ record: traceId=" + traceId);
    }

    /**
     * traceId 기준 done log row 수가 기대값과 같은지 검증합니다.
     */
    private void assertDoneLogCount(String traceId, int expectedCount) {
        assertThat(countDoneLogRows(traceId)).isEqualTo(expectedCount);
    }

    /**
     * traceId 기준 done log row 수를 조회합니다.
     */
    private int countDoneLogRows(String traceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE trace_id = ?",
                Integer.class,
                traceId
        );
        assertThat(count).isNotNull();
        return count;
    }

    /**
     * dedupe key 삭제 outbox가 생성됐는지 확인하기 위해 해당 event row 수를 조회합니다.
     */
    private int countDedupeDeleteOutboxRows(String traceId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM TRAFFIC_REDIS_OUTBOX
                WHERE trace_id = ?
                  AND event_type = 'DELETE_IN_FLIGHT_DEDUPE_KEY'
                """,
                Integer.class,
                traceId
        );
        assertThat(count).isNotNull();
        return count;
    }

    /**
     * 기존 done log 흡수 경로에서 원래 record id가 유지됐는지 확인합니다.
     */
    private String findRecordId(String traceId) {
        return jdbcTemplate.queryForObject(
                "SELECT record_id FROM TRAFFIC_DEDUCT_DONE WHERE trace_id = ?",
                String.class,
                traceId
        );
    }
}
