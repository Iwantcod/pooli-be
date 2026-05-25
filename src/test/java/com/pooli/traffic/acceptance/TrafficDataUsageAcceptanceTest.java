package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamRecords;

import com.pooli.traffic.domain.TrafficStreamFields;

/**
 * 차감 결과가 Redis usage counter와 월별 balance key에 어떤 기준으로 반영되는지 검증하는 인수테스트입니다.
 *
 * <p>처리 source별 counter 반영 규칙, 기존 counter 누적, payload의 enqueuedAt 기준 날짜/월 선택 계약을 다룹니다.</p>
 */
class TrafficDataUsageAcceptanceTest extends TrafficAcceptanceTestSupport {

    private LocalDate rawPayloadUsageDateToCleanup;
    private YearMonth rawPayloadUsageMonthToCleanup;

    /**
     * raw payload 테스트가 현재 날짜가 아닌 key를 만들었을 때 다음 테스트에 남지 않도록 정리합니다.
     */
    @AfterEach
    void cleanupUsageAcceptanceFixture() {
        if (rawPayloadUsageDateToCleanup != null) {
            deleteUsageKeysForDate(LINE_ID_1, rawPayloadUsageDateToCleanup);
        }
        if (rawPayloadUsageMonthToCleanup != null) {
            deleteBalanceAndMonthlyUsageKeys(LINE_ID_1, FAMILY_ID_1, rawPayloadUsageMonthToCleanup);
        }
    }

    /**
     * 개인풀 차감량은 daily total/app counter에만 누적되고 monthly shared counter에는 반영되지 않음을 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-01] 개인풀 처리량은 daily total/app usage에만 반영된다")
    void shouldRecordIndividualDeductionInDailyCountersOnly() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenario(lineId, familyId, 80L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertUsageCounters(lineId, appId, 50L, 50L, 0L);
        assertDailyAppUsageBySource(lineId, appId, 50L, 0L, 0L);
        assertDailySharedUsage(lineId, 0L, 0L);
        assertRedisBalances(lineId, familyId, 30L, 100L);
    }

    /**
     * 공유풀 차감량은 daily counter와 monthly shared counter 양쪽에 누적되는 source별 기록 규칙을 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-02] 공유풀 처리량은 daily total/app usage와 monthly shared usage에 반영된다")
    void shouldRecordSharedDeductionInDailyAndMonthlySharedCounters() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenario(lineId, familyId, 0L, 80L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 50L, 0L, 0L, "SUCCESS", "OK");
        assertUsageCounters(lineId, appId, 50L, 50L, 50L);
        assertDailyAppUsageBySource(lineId, appId, 0L, 50L, 0L);
        assertDailySharedUsage(lineId, 50L, familyId);
        assertRedisBalances(lineId, familyId, 0L, 30L);
    }

    /**
     * 개인풀과 공유풀을 함께 쓴 요청에서 daily는 총 처리량, monthly shared는 공유 처리량만 기록하는지 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-03] 개인+공유 혼합 처리량은 daily에 합산되고 monthly shared에는 공유 처리량만 반영된다")
    void shouldRecordMixedDeductionByCounterSourceRules() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenario(lineId, familyId, 30L, 80L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 20L, 0L, 0L, "SUCCESS", "OK");
        assertUsageCounters(lineId, appId, 50L, 50L, 20L);
        assertDailyAppUsageBySource(lineId, appId, 30L, 20L, 0L);
        assertDailySharedUsage(lineId, 20L, familyId);
        assertRedisBalances(lineId, familyId, 0L, 60L);
    }

    /**
     * QoS 처리량도 daily total/app counter에 반영하되 앱별 hash field는 QoS source로 분리되는지 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-03-1] QoS 처리량은 daily app usage의 qos field에 분리 반영된다")
    void shouldRecordQosDeductionInQosDailyAppField() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenarioWithQos(lineId, familyId, 0L, 0L, 1_000_000L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 50L, 0L, "SUCCESS", "QOS");
        assertUsageCounters(lineId, appId, 50L, 50L, 0L);
        assertDailyAppUsageBySource(lineId, appId, 0L, 0L, 50L);
        assertDailySharedUsage(lineId, 0L, 0L);
        assertRedisBalances(lineId, familyId, 0L, 0L);
    }

    /**
     * 잔량 부족으로 일부만 처리된 요청은 요청량이 아니라 실제 처리량만 counter에 반영해야 함을 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-04] 부분 처리에서는 실제 처리량만 usage counter에 반영된다")
    void shouldRecordOnlyActualDeductedBytesForPartialNoBalance() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenario(lineId, familyId, 10L, 15L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 10L, 15L, 0L, 25L, "PARTIAL_SUCCESS", "NO_BALANCE");
        assertUsageCounters(lineId, appId, 25L, 25L, 15L);
        assertRedisBalances(lineId, familyId, 0L, 0L);
    }

    /**
     * 이미 존재하는 usage counter가 있을 때 덮어쓰지 않고 실제 처리량만큼 누적하는지 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-05] 기존 usage counter가 있으면 실제 처리량만큼 누적된다")
    void shouldAccumulateUsageCountersFromExistingValues() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareUsageScenario(lineId, familyId, 20L, 40L);
        setDailyTotalUsage(lineId, 7L);
        setDailyAppUsage(lineId, appId, 11L);
        setMonthlySharedUsage(lineId, 13L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 20L, 30L, 0L, 0L, "SUCCESS", "OK");
        assertUsageCounters(lineId, appId, 57L, 61L, 43L);
        assertRedisBalances(lineId, familyId, 0L, 10L);
    }

    /**
     * consumer가 처리 시각이 아니라 payload의 enqueuedAt으로 usage 날짜와 balance 월을 결정하는지 검증합니다.
     */
    @Test
    @DisplayName("[USAGE-06] usage counter와 잔량 key는 enqueued_at 기준 날짜/월을 사용한다")
    void shouldUseEnqueuedAtDateAndMonthForUsageAndBalanceKeys() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate targetDate = today.plusMonths(1).withDayOfMonth(1);
        YearMonth targetMonth = YearMonth.from(targetDate);
        rawPayloadUsageDateToCleanup = targetDate;
        rawPayloadUsageMonthToCleanup = targetMonth;
        deleteUsageKeysForDate(lineId, targetDate);
        deleteBalanceAndMonthlyUsageKeys(lineId, familyId, targetMonth);
        prepareUsageScenarioForMonth(lineId, familyId, targetMonth, 10L, 80L);

        String traceId = enqueueRawTrafficPayload(
                lineId,
                familyId,
                appId,
                50L,
                targetDate.atStartOfDay(trafficRedisRuntimePolicy.zoneId()).toInstant().toEpochMilli()
        );

        assertDoneLog(traceId, 10L, 40L, 0L, 0L, "SUCCESS", "OK");
        assertUsageCountersForDate(lineId, appId, targetDate, targetMonth, 50L, 50L, 40L);
        assertDailySharedUsageForDate(lineId, targetDate, 40L, familyId);
        assertUsageCountersForDate(lineId, appId, today, currentMonth, 0L, 0L, 0L);
        assertDailySharedUsageForDate(lineId, today, 0L, 0L);
        assertRedisBalancesForMonth(lineId, familyId, targetMonth, 0L, 40L);
    }

    /**
     * 현재 월 기준 usage 테스트가 필요한 RDB source와 Redis balance를 준비합니다.
     */
    private void prepareUsageScenario(long lineId, long familyId, long individualAmount, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, individualAmount);
        putSharedBalance(familyId, sharedAmount);
    }

    private void prepareUsageScenarioWithQos(
            long lineId,
            long familyId,
            long individualAmount,
            long sharedAmount,
            long qosBytesPerSecond
    ) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalanceWithQos(lineId, individualAmount, qosBytesPerSecond);
        putSharedBalance(familyId, sharedAmount);
    }

    private void putIndividualBalanceWithQos(long lineId, long amount, long qosBytesPerSecond) {
        YearMonth currentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForHash().putAll(
                trafficRedisKeyFactory.remainingIndivAmountKey(lineId, currentMonth),
                Map.of("amount", String.valueOf(amount), "qos", String.valueOf(qosBytesPerSecond))
        );
    }

    /**
     * enqueuedAt 기반 월 선택을 검증하기 위해 지정 월의 balance snapshot을 준비합니다.
     */
    private void prepareUsageScenarioForMonth(
            long lineId,
            long familyId,
            YearMonth targetMonth,
            long individualAmount,
            long sharedAmount
    ) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalanceForMonth(lineId, targetMonth, individualAmount);
        putSharedBalanceForMonth(familyId, targetMonth, sharedAmount);
    }

    /**
     * 지정 월 개인풀 Redis balance hash를 직접 만들어 월별 key 선택을 검증할 수 있게 합니다.
     */
    private void putIndividualBalanceForMonth(long lineId, YearMonth targetMonth, long amount) {
        cacheStringRedisTemplate.opsForHash().putAll(
                trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth),
                Map.of("amount", String.valueOf(amount), "qos", "0")
        );
    }

    /**
     * 지정 월 공유풀 Redis balance hash를 직접 만들어 월별 key 선택을 검증할 수 있게 합니다.
     */
    private void putSharedBalanceForMonth(long familyId, YearMonth targetMonth, long amount) {
        cacheStringRedisTemplate.opsForHash().put(
                trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth),
                "amount",
                String.valueOf(amount)
        );
    }

    /**
     * 기존 daily total usage가 있는 누적 시나리오를 만들기 위해 현재 날짜 counter를 설정합니다.
     */
    private void setDailyTotalUsage(long lineId, long usage) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForValue()
                .set(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, today), String.valueOf(usage));
    }

    /**
     * 기존 app별 daily usage가 있는 누적 시나리오를 만들기 위해 현재 날짜 hash field를 설정합니다.
     */
    private void setDailyAppUsage(long lineId, int appId, long usage) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForHash()
                .put(
                        trafficRedisKeyFactory.dailyAppUsageKey(lineId, today),
                        "app:" + appId + ":individual",
                        String.valueOf(usage)
                );
    }

    /**
     * 기존 monthly shared usage가 있는 누적 시나리오를 만들기 위해 현재 월 counter를 설정합니다.
     */
    private void setMonthlySharedUsage(long lineId, long usage) {
        YearMonth currentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForValue()
                .set(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, currentMonth), String.valueOf(usage));
    }

    /**
     * 현재 날짜/월 기준 usage counter 세 종류가 기대값으로 기록됐는지 검증합니다.
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

    private void assertDailyAppUsageBySource(
            long lineId,
            int appId,
            long expectedIndividualUsage,
            long expectedSharedUsage,
            long expectedQosUsage
    ) {
        assertThat(readDailyAppUsageBySource(lineId, appId, "individual")).isEqualTo(expectedIndividualUsage);
        assertThat(readDailyAppUsageBySource(lineId, appId, "shared")).isEqualTo(expectedSharedUsage);
        assertThat(readDailyAppUsageBySource(lineId, appId, "qos")).isEqualTo(expectedQosUsage);
    }

    private void assertDailySharedUsage(long lineId, long expectedUsageAmount, long expectedFamilyId) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        assertDailySharedUsageForDate(lineId, today, expectedUsageAmount, expectedFamilyId);
    }

    private void assertDailySharedUsageForDate(
            long lineId,
            LocalDate usageDate,
            long expectedUsageAmount,
            long expectedFamilyId
    ) {
        String usageKey = trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate);

        assertThat(readHashCounter(usageKey, "usage_amount")).isEqualTo(expectedUsageAmount);
        assertThat(readHashCounter(usageKey, "family_id")).isEqualTo(expectedFamilyId);
    }

    /**
     * 지정 날짜/월 기준 usage key를 직접 읽어 enqueuedAt 기반 key 선택 결과를 검증합니다.
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
        assertThat(readDailyAppUsageForDate(lineId, appId, usageDate))
                .isEqualTo(expectedDailyAppUsage);
        assertThat(readStringCounter(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, usageMonth)))
                .isEqualTo(expectedMonthlySharedUsage);
    }

    /**
     * 현재 월 개인풀/공유풀 잔량이 차감 후 기대값으로 남았는지 검증합니다.
     */
    private void assertRedisBalances(
            long lineId,
            long familyId,
            long expectedIndividualAmount,
            long expectedSharedAmount
    ) {
        assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(expectedIndividualAmount);
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(expectedSharedAmount);
    }

    /**
     * 지정 월 개인풀/공유풀 잔량이 차감 후 기대값으로 남았는지 검증합니다.
     */
    private void assertRedisBalancesForMonth(
            long lineId,
            long familyId,
            YearMonth targetMonth,
            long expectedIndividualAmount,
            long expectedSharedAmount
    ) {
        assertThat(readHashCounter(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth), "amount"))
                .isEqualTo(expectedIndividualAmount);
        assertThat(readHashCounter(trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth), "amount"))
                .isEqualTo(expectedSharedAmount);
    }

    /**
     * Redis string counter를 long으로 읽고, 아직 생성되지 않은 counter는 0으로 취급합니다.
     */
    private long readStringCounter(String key) {
        String value = cacheStringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    /**
     * Redis hash counter field를 long으로 읽고, 아직 생성되지 않은 field는 0으로 취급합니다.
     */
    private long readHashCounter(String key, String field) {
        Object value = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private long readDailyAppUsageForDate(long lineId, int appId, LocalDate usageDate) {
        String usageKey = trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate);
        return readHashCounter(usageKey, "app:" + appId + ":individual")
                + readHashCounter(usageKey, "app:" + appId + ":shared")
                + readHashCounter(usageKey, "app:" + appId + ":qos");
    }

    /**
     * API를 우회해 원하는 enqueuedAt 값을 가진 payload를 stream에 직접 적재합니다.
     */
    private String enqueueRawTrafficPayload(
            long lineId,
            long familyId,
            int appId,
            long apiTotalData,
            long enqueuedAt
    ) {
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

    /**
     * raw payload 테스트가 만든 특정 날짜의 daily usage key를 정리합니다.
     */
    private void deleteUsageKeysForDate(long lineId, LocalDate usageDate) {
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate));
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate));
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate));
    }

    /**
     * raw payload 테스트가 만든 특정 월의 balance와 monthly shared usage key를 정리합니다.
     */
    private void deleteBalanceAndMonthlyUsageKeys(long lineId, long familyId, YearMonth targetMonth) {
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth));
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth));
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth));
    }
}
