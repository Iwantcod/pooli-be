package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamRecords;

import com.pooli.traffic.domain.TrafficStreamFields;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * 여러 stream record가 동시에 처리될 때 Redis 잔량, limit, dedupe 불변식이 깨지지 않는지 검증합니다.
 *
 * <p>각 테스트는 개별 record 성공 여부보다 전체 처리량 합계가 잔량 또는 정책 한도를 넘지 않는지를 중심으로 확인합니다.</p>
 */
class TrafficDataConcurrencyAcceptanceTest extends TrafficAcceptanceTestSupport {

    private final Set<String> traceIdsToCleanup = new LinkedHashSet<>();

    /**
     * 중복 trace 테스트가 만든 dedupe run key를 제거해 다음 동시성 테스트에 영향을 주지 않게 합니다.
     */
    @AfterEach
    void cleanupConcurrencyFixture() {
        for (String traceId : traceIdsToCleanup) {
            cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dedupeRunKey(traceId));
        }
    }

    /**
     * 개인풀만 있는 상태에서 동시 요청 합산 차감량이 개인풀 잔량을 넘지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-01] 개인풀 동시 요청 처리량 합계는 개인풀 잔량을 넘지 않는다")
    void shouldNotOverDeductIndividualBalanceForConcurrentRequests() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareConcurrencyScenario(lineId, familyId, 100L, 0L);

        List<TrafficDeductDoneLog> doneLogs = enqueueBurstAndAwait(lineId, familyId, appId, 6, 25L);

        assertTotals(doneLogs, 100L, 0L, 50L);
        assertRedisState(lineId, familyId, appId, 0L, 0L, 100L, 100L, 0L);
    }

    /**
     * 공유풀만 있는 상태에서 동시 요청 합산 차감량이 공유풀 잔량을 넘지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-02] 공유풀 동시 요청 처리량 합계는 공유풀 잔량을 넘지 않는다")
    void shouldNotOverDeductSharedBalanceForConcurrentRequests() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareConcurrencyScenario(lineId, familyId, 0L, 100L);

        List<TrafficDeductDoneLog> doneLogs = enqueueBurstAndAwait(lineId, familyId, appId, 6, 25L);

        assertTotals(doneLogs, 0L, 100L, 50L);
        assertRedisState(lineId, familyId, appId, 0L, 0L, 100L, 100L, 100L);
    }

    /**
     * 개인풀과 공유풀이 함께 있을 때 source별 CAS 처리량 합계가 전체 잔량 합계를 넘지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-03] 개인+공유 동시 요청 처리량 합계는 두 풀 잔량 합계를 넘지 않는다")
    void shouldNotOverDeductCombinedBalancesForConcurrentRequests() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareConcurrencyScenario(lineId, familyId, 60L, 60L);

        List<TrafficDeductDoneLog> doneLogs = enqueueBurstAndAwait(lineId, familyId, appId, 6, 25L);

        assertTotals(doneLogs, 60L, 60L, 30L);
        assertRedisState(lineId, familyId, appId, 0L, 0L, 120L, 120L, 60L);
    }

    /**
     * 동시 요청이 몰려도 daily total limit이 전체 처리량의 상한으로 유지되는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-04] 일일 총량 동시 요청 처리량 합계는 daily limit을 넘지 않는다")
    void shouldNotExceedDailyLimitForConcurrentRequests() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareConcurrencyScenario(lineId, familyId, 200L, 200L);
        setDailyTotalLimit(lineId, 100L);
        markLinePolicyReady(lineId);

        List<TrafficDeductDoneLog> doneLogs = enqueueBurstAndAwait(lineId, familyId, appId, 6, 25L);

        assertTotals(doneLogs, 100L, 0L, 50L);
        assertRedisState(lineId, familyId, appId, 100L, 200L, 100L, 100L, 0L);
    }

    /**
     * 공유풀 동시 요청이 몰려도 monthly shared limit이 공유 처리량의 상한으로 유지되는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-05] 월 공유 한도 동시 요청 처리량 합계는 monthly shared limit을 넘지 않는다")
    void shouldNotExceedMonthlySharedLimitForConcurrentRequests() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareConcurrencyScenario(lineId, familyId, 0L, 200L);
        setMonthlySharedLimit(lineId, 100L);
        markLinePolicyReady(lineId);

        List<TrafficDeductDoneLog> doneLogs = enqueueBurstAndAwait(lineId, familyId, appId, 6, 25L);

        assertTotals(doneLogs, 0L, 100L, 50L);
        assertRedisState(lineId, familyId, appId, 0L, 100L, 100L, 100L, 100L);
    }

    /**
     * 같은 traceId record가 동시에 여러 번 들어와도 dedupe가 api_total_data 이상의 중복 차감을 막는지 검증합니다.
     */
    @Test
    @DisplayName("[CON-06] 같은 traceId 동시 중복 메시지는 api_total_data를 초과 차감하지 않는다")
    void shouldNotDeductDuplicateConcurrentMessagesWithSameTraceIdMoreThanApiTotalData() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        String traceId = newTraceId();
        prepareConcurrencyScenario(lineId, familyId, 100L, 100L);

        enqueueDuplicateTraceBurst(traceId, lineId, familyId, appId, 6, 50L);
        awaitDoneLog(traceId);
        await("duplicate records settle", () -> countDoneLogRows(traceId) == 1 && readDailyTotalUsage(lineId) == 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertThat(countDoneLogRows(traceId)).isEqualTo(1);
        assertRedisState(lineId, familyId, appId, 50L, 100L, 50L, 50L, 0L);
    }

    /**
     * 동시성 테스트가 사용할 RDB source와 Redis balance snapshot을 준비합니다.
     */
    private void prepareConcurrencyScenario(long lineId, long familyId, long individualAmount, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, individualAmount);
        putSharedBalance(familyId, sharedAmount);
    }

    /**
     * 서로 다른 traceId 요청을 burst로 stream에 적재하고 모든 done log가 생성될 때까지 기다립니다.
     */
    private List<TrafficDeductDoneLog> enqueueBurstAndAwait(
            long lineId,
            long familyId,
            int appId,
            int requestCount,
            long apiTotalData
    ) throws Exception {
        List<String> traceIds = IntStream.range(0, requestCount)
                .mapToObj(ignored -> newTraceId())
                .toList();
        for (String traceId : traceIds) {
            enqueueRawTrafficPayload(traceId, lineId, familyId, appId, apiTotalData);
        }

        List<TrafficDeductDoneLog> doneLogs = new ArrayList<>();
        for (String traceId : traceIds) {
            doneLogs.add(awaitDoneLog(traceId));
        }
        return doneLogs;
    }

    /**
     * 같은 traceId를 가진 여러 record를 stream에 넣어 dedupe 경쟁 상황을 만듭니다.
     */
    private void enqueueDuplicateTraceBurst(
            String traceId,
            long lineId,
            long familyId,
            int appId,
            int requestCount,
            long apiTotalData
    ) {
        for (int i = 0; i < requestCount; i++) {
            enqueueRawTrafficPayload(traceId, lineId, familyId, appId, apiTotalData);
        }
    }

    /**
     * acceptance 전용 traceId를 만들고, 테스트 후 dedupe key 정리 대상으로 등록합니다.
     */
    private String newTraceId() {
        String traceId = "acceptance-" + UUID.randomUUID();
        traceIdsToCleanup.add(traceId);
        return traceId;
    }

    /**
     * API를 우회해 원하는 traceId를 가진 payload를 stream에 직접 적재합니다.
     */
    private void enqueueRawTrafficPayload(
            String traceId,
            long lineId,
            long familyId,
            int appId,
            long apiTotalData
    ) {
        String payloadJson = """
                {
                  "traceId": "%s",
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": %d,
                  "enqueuedAt": %d
                }
                """.formatted(traceId, lineId, familyId, appId, apiTotalData, System.currentTimeMillis());
        streamsStringRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of(TrafficStreamFields.PAYLOAD, payloadJson))
                        .withStreamKey(appStreamsProperties.getKeyTrafficRequest())
        );
    }

    /**
     * daily limit 동시성 시나리오를 위해 Redis 정책 snapshot을 설정합니다.
     */
    private void setDailyTotalLimit(long lineId, long limit) {
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.dailyTotalLimitKey(lineId), "value", String.valueOf(limit));
    }

    /**
     * monthly shared limit 동시성 시나리오를 위해 Redis 정책 snapshot을 설정합니다.
     */
    private void setMonthlySharedLimit(long lineId, long limit) {
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.monthlySharedLimitKey(lineId), "value", String.valueOf(limit));
    }

    /**
     * line 정책 snapshot이 준비된 것으로 표시해 테스트가 설정한 Redis limit을 바로 사용하게 합니다.
     */
    private void markLinePolicyReady(long lineId) {
        cacheStringRedisTemplate.opsForValue().set(trafficRedisKeyFactory.linePolicyReadyKey(lineId), "1");
    }

    /**
     * 여러 done log의 source별 처리량과 남은 요청량을 합산해 동시성 불변식을 검증합니다.
     */
    private void assertTotals(
            List<TrafficDeductDoneLog> doneLogs,
            long expectedIndividualBytes,
            long expectedSharedBytes,
            long expectedRemainingBytes
    ) {
        long actualIndividualBytes = doneLogs.stream()
                .mapToLong(TrafficDeductDoneLog::getDeductedIndividualBytes)
                .sum();
        long actualSharedBytes = doneLogs.stream()
                .mapToLong(TrafficDeductDoneLog::getDeductedSharedBytes)
                .sum();
        long actualQosBytes = doneLogs.stream()
                .mapToLong(TrafficDeductDoneLog::getDeductedQosBytes)
                .sum();
        long actualRemainingBytes = doneLogs.stream()
                .mapToLong(TrafficDeductDoneLog::getApiRemainingData)
                .sum();

        assertThat(actualIndividualBytes).isEqualTo(expectedIndividualBytes);
        assertThat(actualSharedBytes).isEqualTo(expectedSharedBytes);
        assertThat(actualQosBytes).isZero();
        assertThat(actualRemainingBytes).isEqualTo(expectedRemainingBytes);
    }

    /**
     * 동시 처리 후 Redis 잔량과 usage counter가 합산 기대값으로 수렴했는지 검증합니다.
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
     * 같은 traceId에 대해 done log가 몇 개 생성됐는지 조회해 dedupe 결과를 검증합니다.
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
}
