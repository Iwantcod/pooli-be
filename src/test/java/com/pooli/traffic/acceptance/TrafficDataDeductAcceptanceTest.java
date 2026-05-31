package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * 트래픽 차감 consumer가 개인풀, 공유풀, 무제한 sentinel, 잔량 부족을 어떤 순서와 상태로 처리하는지 검증합니다.
 *
 * <p>각 테스트는 done log의 최종 상태뿐 아니라 Redis 잔량/usage counter와 RDB hydrate source 불변성까지 함께 확인합니다.</p>
 */
class TrafficDataDeductAcceptanceTest extends TrafficAcceptanceTestSupport {

    /**
     * Redis snapshot이 없어도 RDB source로 개인풀을 hydrate한 뒤 개인풀에서만 차감되는 기본 성공 경로를 검증합니다.
     */
    @Test
    @DisplayName("[DED-01] 개인풀 hydrate 후 개인풀 충분 차감은 SUCCESS/OK로 끝난다")
    void shouldDeductIndividualBalanceThroughStreamConsumer() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();

        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        TrafficDeductDoneLog doneLog = assertDoneLog(
                traceId,
                50L,
                0L,
                0L,
                0L,
                "SUCCESS",
                "OK"
        );

        await("individual balance is decremented in Redis", () -> readIndividualBalanceAmount(lineId) == 150L);
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(0L);
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(50L);
        assertThat(readDailyAppUsage(lineId, appId)).isEqualTo(50L);
        assertThat(readMonthlySharedUsage(lineId)).isEqualTo(0L);
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
        assertThat(doneLog.getLineId()).isEqualTo(lineId);
        assertThat(doneLog.getFamilyId()).isEqualTo(familyId);
        assertThat(doneLog.getAppId()).isEqualTo(appId);
    }

    /**
     * 개인풀 잔량을 정확히 소진하는 경계값에서 성공 상태와 usage 반영이 깨지지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[DED-02] 개인풀 잔량과 요청량이 정확히 같으면 개인풀 전량 차감 후 SUCCESS/OK로 끝난다")
    void shouldDeductExactIndividualBalance() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 50L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 개인풀이 비어 있으면 공유풀로 fallback해 전량 처리하는 차감 순서 계약을 검증합니다.
     */
    @Test
    @DisplayName("[DED-03] 개인풀 0, 공유풀 충분이면 공유풀에서 전량 차감 후 SUCCESS/OK로 끝난다")
    void shouldDeductSharedBalanceWhenIndividualBalanceIsEmpty() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 0L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 50L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 50L, 50L, 50L, 50L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 개인풀을 먼저 소진한 뒤 부족분만 공유풀에서 처리하는 혼합 차감 계약을 검증합니다.
     */
    @Test
    @DisplayName("[DED-04] 개인풀 일부와 공유풀 일부를 함께 사용해 전량 처리하면 SUCCESS/OK로 끝난다")
    void shouldDeductIndividualAndSharedBalance() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 30L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 20L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 80L, 50L, 50L, 20L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 개인풀과 공유풀 합계가 요청량과 같은 경계값에서 두 잔량이 모두 0으로 수렴하는지 검증합니다.
     */
    @Test
    @DisplayName("[DED-05] 개인풀+공유풀 합계가 요청량과 정확히 같으면 두 풀을 모두 소진하고 SUCCESS/OK로 끝난다")
    void shouldDeductExactIndividualAndSharedBalance() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 30L, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 20L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 0L, 50L, 50L, 20L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 전체 잔량이 부족할 때 가능한 만큼만 차감하고 남은 요청량을 done log에 남기는 부분 성공 계약을 검증합니다.
     */
    @Test
    @DisplayName("[DED-06] 개인풀+공유풀 합계가 요청량보다 작으면 PARTIAL_SUCCESS/NO_BALANCE로 끝난다")
    void shouldReturnPartialSuccessWhenBothBalancesAreInsufficient() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 10L, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 10L, 20L, 0L, 20L, "PARTIAL_SUCCESS", "NO_BALANCE");
        assertRedisState(lineId, familyId, appId, 0L, 0L, 30L, 30L, 20L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 처리 가능한 잔량이 전혀 없을 때 usage counter를 증가시키지 않는 미차감 계약을 검증합니다.
     */
    @Test
    @DisplayName("[DED-07] 개인풀 0, 공유풀 0이면 차감 없이 NOT_DEDUCTED/NO_BALANCE로 끝난다")
    void shouldNotDeductWhenBothBalancesAreEmpty() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 0L, 0L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "NO_BALANCE");
        assertRedisState(lineId, familyId, appId, 0L, 0L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 개인풀 무제한 sentinel은 잔량 값을 감소시키지 않고 처리량만 기록해야 함을 검증합니다.
     */
    @Test
    @DisplayName("[DED-08] 개인풀 무제한이면 amount를 유지하고 개인풀 처리량만 기록한 뒤 SUCCESS/OK로 끝난다")
    void shouldKeepUnlimitedIndividualBalanceAmount() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, -1L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, -1L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 공유풀 무제한 sentinel은 공유풀 amount를 유지하면서 공유 처리량과 usage만 반영해야 함을 검증합니다.
     */
    @Test
    @DisplayName("[DED-09] 공유풀 무제한이면 amount를 유지하고 공유풀 처리량만 기록한 뒤 SUCCESS/OK로 끝난다")
    void shouldKeepUnlimitedSharedBalanceAmount() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 0L, -1L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 50L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, -1L, 50L, 50L, 50L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 요청량 0은 잔량과 usage를 변경하지 않는 no-op 차감으로 처리되는지 검증합니다.
     */
    @Test
    @DisplayName("[DED-10] 요청량 0이면 잔량과 usage counter를 바꾸지 않고 NOT_DEDUCTED/OK로 끝난다")
    void shouldKeepBalancesAndUsageWhenRequestDataIsZero() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        prepareRedisBalances(lineId, familyId, 50L, 100L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 0L);

        assertDoneLog(traceId, 0L, 0L, 0L, 0L, "NOT_DEDUCTED", "OK");
        assertRedisState(lineId, familyId, appId, 50L, 100L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * hydrate source와 Redis balance snapshot을 함께 준비해 차감 로직만 고립해서 검증할 수 있게 합니다.
     */
    private void prepareRedisBalances(long lineId, long familyId, long individualAmount, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, individualAmount);
        putSharedBalance(familyId, sharedAmount);
    }

    /**
     * 차감 이후 Redis 잔량과 usage counter가 source별 기대값으로 정리됐는지 한 번에 검증합니다.
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
     * Redis-only 차감이 RDB hydrate source 값을 직접 감소시키지 않았음을 검증합니다.
     */
    private void assertRdbSourcesUnchanged(long lineId, long familyId) {
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
    }
}
