package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 전역 정책 flag와 line/app별 정책 snapshot이 차감 가능량, 차단 상태, 정책 우선순위에 미치는 영향을 검증합니다.
 *
 * <p>일일/앱/공유 한도, 즉시/반복 차단, whitelist, 전체 정책 조합 matrix를 실제 Redis snapshot 기반으로 고정합니다.</p>
 */
class TrafficDataPolicyAcceptanceTest extends TrafficAcceptanceTestSupport {

    private static final int POLICY_REPEAT_BLOCK = 1;
    private static final int POLICY_IMMEDIATE_BLOCK = 2;
    private static final int POLICY_LINE_LIMIT_SHARED = 3;
    private static final int POLICY_LINE_LIMIT_DAILY = 4;
    private static final int POLICY_APP_DATA = 5;
    private static final int POLICY_APP_SPEED = 6;
    private static final int POLICY_APP_WHITELIST = 7;

    /**
     * 일일 총량 정책이 활성화되면 요청량보다 낮은 daily limit까지만 처리하는 기본 cap 계약을 검증합니다.
     */
    @Test
    @DisplayName("[POL-01] 일일 총량 정책이 활성이고 limit보다 요청이 크면 limit까지만 처리한다")
    void shouldCapByActiveDailyTotalPolicy() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setDailyTotalLimit(lineId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 30L, 0L, 0L, 20L, "PARTIAL_SUCCESS", "HIT_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 70L, 100L, 30L, 30L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 전역 daily flag가 꺼져 있으면 line limit snapshot이 있어도 차감량을 제한하지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-02] 일일 총량 전역 정책이 비활성이면 limit record가 있어도 우회한다")
    void shouldBypassDailyTotalLimitWhenGlobalPolicyIsInactive() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setGlobalPolicy(POLICY_LINE_LIMIT_DAILY, false);
        setDailyTotalLimit(lineId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 50L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 앱 일일 데이터 정책이 활성화되면 앱별 limit까지 처리하고 남은 요청량을 기록하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-03] 앱 일일 데이터 정책이 활성이고 limit보다 요청이 크면 limit까지만 처리한다")
    void shouldCapByActiveAppDailyPolicy() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setAppDailyLimit(lineId, appId, 25L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 25L, 0L, 0L, 25L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 75L, 100L, 25L, 25L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 전역 app data flag가 꺼져 있으면 앱별 limit snapshot이 있어도 차감량을 제한하지 않는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-04] 앱 일일 데이터 전역 정책이 비활성이면 app limit record가 있어도 우회한다")
    void shouldBypassAppDailyLimitWhenGlobalPolicyIsInactive() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setGlobalPolicy(POLICY_APP_DATA, false);
        setAppDailyLimit(lineId, appId, 25L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 50L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 월 공유 한도는 전체 요청량이 아니라 공유풀에서 처리되는 바이트만 제한하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-05] 월 공유 한도 정책은 공유풀 처리량만 제한한다")
    void shouldCapOnlySharedDeductionByActiveMonthlySharedPolicy() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 0L, 100L);
        setMonthlySharedLimit(lineId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 30L, 0L, 20L, "PARTIAL_SUCCESS", "HIT_MONTHLY_SHARED_LIMIT");
        assertRedisState(lineId, familyId, appId, 0L, 70L, 30L, 30L, 30L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 전역 shared limit flag가 꺼져 있으면 월 공유 limit snapshot이 있어도 공유풀 차감을 허용하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-06] 월 공유 한도 전역 정책이 비활성이면 shared limit record가 있어도 우회한다")
    void shouldBypassMonthlySharedLimitWhenGlobalPolicyIsInactive() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 0L, 100L);
        setGlobalPolicy(POLICY_LINE_LIMIT_SHARED, false);
        setMonthlySharedLimit(lineId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 50L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 0L, 50L, 50L, 50L, 50L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * daily usage가 이미 limit에 도달한 상태에서는 잔량이 있어도 차감하지 않는 exhausted 상태를 검증합니다.
     */
    @Test
    @DisplayName("[POL-07] 일일 총량 사용량이 이미 limit에 도달했으면 차감 없이 NOT_DEDUCTED가 된다")
    void shouldNotDeductWhenDailyTotalUsageAlreadyReachedLimit() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setDailyTotalLimit(lineId, 30L);
        setDailyTotalUsage(lineId, 30L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "HIT_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 30L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 앱별 daily usage가 limit에 도달한 상태에서는 해당 앱 요청을 차감하지 않는 exhausted 상태를 검증합니다.
     */
    @Test
    @DisplayName("[POL-08] 앱 일일 사용량이 이미 limit에 도달했으면 차감 없이 NOT_DEDUCTED가 된다")
    void shouldNotDeductWhenAppDailyUsageAlreadyReachedLimit() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setAppDailyLimit(lineId, appId, 20L);
        setDailyAppUsage(lineId, appId, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "HIT_APP_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 20L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 앱 속도 제한은 회선+앱 단일 예약 키에 요청 완료 예정 시각을 기록합니다.
     */
    @Test
    @DisplayName("[POL-09] 앱 속도 제한은 요청 시각 이후 완료 예정 시각으로 예약 키를 갱신한다")
    void shouldUpdateAppSpeedReservationAfterRequestTime() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        long requestBytes = 1_300_000L;
        int speedBytesPerSecond = 625_000;
        long expectedDurationMs = (requestBytes * 1_000 + speedBytesPerSecond - 1) / speedBytesPerSecond;
        preparePolicyScenario(lineId, familyId, 0L, 2_000_000L);
        setGlobalPolicy(POLICY_APP_SPEED, true);
        setAppSpeedLimit(lineId, appId, speedBytesPerSecond);
        deleteAppSpeedReservation(lineId, appId);

        long beforeRequestEpochMillis = System.currentTimeMillis();
        String traceId = enqueueTrafficRequest(lineId, familyId, appId, requestBytes);

        var doneLog = assertDoneLog(traceId, 0L, requestBytes, 0L, 0L, "SUCCESS", "HIT_APP_SPEED");
        long reservationEpochMillis = readAppSpeedReservation(lineId, appId);
        long finishedAtEpochMillis = doneLog.getFinishedAt()
                .atZone(trafficRedisRuntimePolicy.zoneId())
                .toInstant()
                .toEpochMilli();
        assertThat(reservationEpochMillis).isEqualTo(finishedAtEpochMillis);
        assertThat(reservationEpochMillis).isGreaterThanOrEqualTo(beforeRequestEpochMillis + expectedDurationMs);
        assertRedisState(lineId, familyId, appId, 0L, 700_000L, requestBytes, requestBytes, requestBytes);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 모든 limit flag가 꺼진 조합에서는 limit snapshot이 존재해도 잔량 기준으로만 처리하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-01] line daily/shared/app daily flag가 모두 비활성이면 모든 limit을 우회한다")
    void shouldBypassAllLimitsWhenAllLimitFlagsAreInactive() throws Exception {
        assertLimitFlagCombination(false, false, false, 10L, 40L, 40L, "SUCCESS", "OK", 50L);
    }

    /**
     * app daily flag만 켜진 조합에서 앱 limit이 첫 제한 상태로 선택되는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-02] app daily flag만 활성화되면 app limit까지만 처리한다")
    void shouldApplyOnlyAppDailyLimitWhenOnlyAppFlagIsActive() throws Exception {
        assertLimitFlagCombination(false, false, true, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT", 30L);
    }

    /**
     * shared flag만 켜진 조합에서 공유풀 처리량만 monthly shared limit으로 제한되는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-03] monthly shared flag만 활성화되면 공유풀 처리량만 shared limit으로 제한한다")
    void shouldApplyOnlyMonthlySharedLimitWhenOnlySharedFlagIsActive() throws Exception {
        assertLimitFlagCombination(false, true, false, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_MONTHLY_SHARED_LIMIT", 30L);
    }

    /**
     * shared와 app flag가 함께 켜졌을 때 더 먼저 도달하는 app cap 상태가 최종 상태가 되는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-04] shared와 app flag가 활성화되면 더 이른 app cap 상태를 유지한다")
    void shouldApplyAppStatusWhenSharedAndAppFlagsAreActive() throws Exception {
        assertLimitFlagCombination(false, true, true, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT", 30L);
    }

    /**
     * daily flag만 켜진 조합에서 전체 처리량이 daily limit으로 제한되는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-05] line daily flag만 활성화되면 daily limit까지만 처리한다")
    void shouldApplyOnlyDailyLimitWhenOnlyDailyFlagIsActive() throws Exception {
        assertLimitFlagCombination(true, false, false, 10L, 30L, 30L, "PARTIAL_SUCCESS", "HIT_DAILY_LIMIT", 40L);
    }

    /**
     * daily와 app flag가 함께 켜졌을 때 더 낮은 app limit이 처리량을 결정하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-06] daily와 app flag가 활성화되면 더 낮은 app limit까지만 처리한다")
    void shouldApplyLowerAppLimitWhenDailyAndAppFlagsAreActive() throws Exception {
        assertLimitFlagCombination(true, false, true, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT", 30L);
    }

    /**
     * daily와 shared flag가 함께 켜졌을 때 공유풀 구간이 monthly shared limit으로 잘리는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-07] daily와 shared flag가 활성화되면 shared limit이 공유풀 처리량을 제한한다")
    void shouldApplySharedLimitWhenDailyAndSharedFlagsAreActive() throws Exception {
        assertLimitFlagCombination(true, true, false, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_MONTHLY_SHARED_LIMIT", 30L);
    }

    /**
     * 세 limit flag가 모두 켜진 조합에서 가장 먼저 도달하는 app cap 결과를 유지하는지 검증합니다.
     */
    @Test
    @DisplayName("[POL-LIMIT-08] daily/shared/app flag가 모두 활성화되면 app cap 상태로 처리량이 제한된다")
    void shouldApplyAppCapWhenAllLimitFlagsAreActive() throws Exception {
        assertLimitFlagCombination(true, true, true, 10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT", 30L);
    }

    /**
     * 즉시 차단과 반복 차단이 동시에 존재할 때 즉시 차단이 우선 상태로 남는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-01] 즉시 차단은 반복 차단보다 먼저 평가된다")
    void shouldApplyImmediateBlockBeforeRepeatBlock() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setImmediateBlock(lineId);
        setRepeatBlockForNow(lineId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_IMMEDIATE");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 반복 차단이 활성화되면 잔량과 limit 평가 전에 요청을 중단하는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-02] 반복 차단은 잔량/한도 차감보다 먼저 평가된다")
    void shouldApplyRepeatBlockBeforeDeductionLimits() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setRepeatBlockForNow(lineId);
        setDailyTotalLimit(lineId, 20L);
        setAppDailyLimit(lineId, appId, 20L);
        setMonthlySharedLimit(lineId, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_REPEAT");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * daily limit과 app cap이 모두 제한 가능할 때 daily limit이 먼저 적용되는 우선순위를 검증합니다.
     */
    @Test
    @DisplayName("[PRI-03] daily와 app cap이 함께 있으면 daily cap이 먼저 적용된다")
    void shouldApplyDailyLimitBeforeAppDailyLimit() throws Exception {
        assertPriorityScenario(100L, 100L, 20L, 30L, -1L, 20L, 0L, "HIT_DAILY_LIMIT");
    }

    /**
     * daily limit이 요청을 자르지 않는 경우 app daily cap으로 제한 상태가 넘어가는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-04] daily가 제한하지 않으면 app daily cap이 적용된다")
    void shouldApplyAppDailyLimitWhenDailyLimitDoesNotCap() throws Exception {
        assertPriorityScenario(100L, 100L, 40L, 30L, -1L, 30L, 0L, "HIT_APP_DAILY_LIMIT");
    }

    /**
     * daily와 app cap이 제한하지 않으면 공유풀 처리 구간에서 monthly shared cap이 적용되는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-05] daily/app이 제한하지 않으면 monthly shared cap이 적용된다")
    void shouldApplyMonthlySharedLimitAfterDailyAndAppLimits() throws Exception {
        assertPriorityScenario(0L, 100L, 50L, 50L, 30L, 0L, 30L, "HIT_MONTHLY_SHARED_LIMIT");
    }

    /**
     * daily limit이 개인풀 처리량뿐 아니라 공유풀 처리량까지 포함한 전체 처리량을 제한하는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-06] daily limit은 개인풀뿐 아니라 공유풀 차감량도 제한한다")
    void shouldApplyDailyLimitToSharedDeduction() throws Exception {
        assertPriorityScenario(0L, 100L, 30L, -1L, -1L, 0L, 30L, "HIT_DAILY_LIMIT");
    }

    /**
     * daily usage가 이미 소진되면 app/shared 정책보다 먼저 미차감 상태로 종료하는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-07] daily 사용량이 이미 limit에 도달하면 app/shared보다 먼저 차단된다")
    void shouldStopAtDailyLimitBeforeAppAndSharedWhenDailyUsageIsExhausted() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 0L, 100L);
        setDailyTotalLimit(lineId, 20L);
        setDailyTotalUsage(lineId, 20L);
        setAppDailyLimit(lineId, appId, 20L);
        setMonthlySharedLimit(lineId, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "HIT_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 0L, 100L, 20L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * app usage가 이미 소진되면 shared cap 평가 전에 미차감 상태로 종료하는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-08] app 사용량이 이미 limit에 도달하면 shared cap보다 먼저 차단된다")
    void shouldStopAtAppDailyLimitBeforeSharedWhenAppUsageIsExhausted() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 0L, 100L);
        setDailyTotalLimit(lineId, 100L);
        setAppDailyLimit(lineId, appId, 20L);
        setDailyAppUsage(lineId, appId, 20L);
        setMonthlySharedLimit(lineId, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "HIT_APP_DAILY_LIMIT");
        assertRedisState(lineId, familyId, appId, 0L, 100L, 0L, 20L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * monthly shared usage가 이미 소진된 상태에서는 개인풀 처리 후 공유풀 차감을 중단하는지 검증합니다.
     */
    @Test
    @DisplayName("[PRI-09] monthly shared 사용량이 이미 limit에 도달하면 개인풀만 처리하고 부분 성공한다")
    void shouldStopSharedDeductionWhenMonthlySharedUsageIsExhausted() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 10L, 100L);
        setDailyTotalLimit(lineId, 100L);
        setAppDailyLimit(lineId, appId, 100L);
        setMonthlySharedLimit(lineId, 20L);
        setMonthlySharedUsage(lineId, 20L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 10L, 0L, 0L, 40L, "PARTIAL_SUCCESS", "HIT_MONTHLY_SHARED_LIMIT");
        assertRedisState(lineId, familyId, appId, 0L, 100L, 10L, 10L, 20L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * whitelist 앱은 차단/limit 정책을 우회하되 실제 잔량 차감과 usage 기록은 수행하는지 검증합니다.
     */
    @Test
    @DisplayName("[WL-01] whitelist 앱은 즉시 차단과 제한 정책을 우회한다")
    void shouldBypassImmediateBlockAndLimitsForWhitelistedApp() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setImmediateBlock(lineId);
        setDailyTotalLimit(lineId, 10L);
        setAppDailyLimit(lineId, appId, 10L);
        setMonthlySharedLimit(lineId, 10L);
        setAppWhitelist(lineId, appId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 50L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * whitelist에 포함되지 않은 앱은 즉시 차단 정책을 그대로 적용받는지 검증합니다.
     */
    @Test
    @DisplayName("[WL-02] whitelist가 아닌 앱은 즉시 차단을 우회하지 못한다")
    void shouldNotBypassImmediateBlockForNonWhitelistedApp() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setImmediateBlock(lineId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_IMMEDIATE");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 전역 whitelist flag가 꺼져 있으면 whitelist record가 있어도 우회가 비활성화되는지 검증합니다.
     */
    @Test
    @DisplayName("[WL-03] whitelist 전역 정책이 비활성이면 whitelist record가 있어도 우회하지 못한다")
    void shouldNotBypassWhenWhitelistGlobalPolicyIsInactive() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setGlobalPolicy(POLICY_APP_WHITELIST, false);
        setImmediateBlock(lineId);
        setAppWhitelist(lineId, appId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_IMMEDIATE");
        assertRedisState(lineId, familyId, appId, 100L, 100L, 0L, 0L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * whitelist는 정책만 우회하고 실제 개인/공유 잔량 부족까지 성공으로 바꾸지는 않는지 검증합니다.
     */
    @Test
    @DisplayName("[WL-04] whitelist는 정책만 우회하고 실제 잔량 부족은 우회하지 않는다")
    void shouldNotBypassActualBalanceShortageForWhitelistedApp() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 0L, 20L);
        setDailyTotalLimit(lineId, 10L);
        setAppDailyLimit(lineId, appId, 10L);
        setMonthlySharedLimit(lineId, 10L);
        setAppWhitelist(lineId, appId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 0L, 20L, 0L, 30L, "PARTIAL_SUCCESS", "NO_BALANCE");
        assertRedisState(lineId, familyId, appId, 0L, 0L, 20L, 20L, 20L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * whitelist 앱이 반복 차단 정책도 우회할 수 있는 정책 예외 계약을 검증합니다.
     */
    @Test
    @DisplayName("[WL-05] whitelist 앱은 반복 차단도 우회한다")
    void shouldBypassRepeatBlockForWhitelistedApp() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 100L, 100L);
        setRepeatBlockForNow(lineId);
        setAppWhitelist(lineId, appId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(traceId, 50L, 0L, 0L, 0L, "SUCCESS", "OK");
        assertRedisState(lineId, familyId, appId, 50L, 100L, 50L, 50L, 0L);
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 주요 정책 flag의 모든 활성 조합에서 첫 제한 정책과 처리량이 기대 matrix와 일치하는지 검증합니다.
     */
    @ParameterizedTest(name = "[MATRIX-01] policies={0}")
    @MethodSource("policyMatrixCases")
    @DisplayName("[MATRIX-01] 정책 1~5,7 활성 조합별 첫 제한 정책을 검증한다")
    void shouldResolveExpectedFirstLimitingPolicyForPolicyMatrix(
            String caseName,
            boolean repeatActive,
            boolean immediateActive,
            boolean sharedActive,
            boolean dailyActive,
            boolean appActive,
            boolean whitelistActive,
            long expectedIndividualBytes,
            long expectedSharedBytes,
            long expectedRemainingBytes,
            String expectedFinalStatus,
            String expectedLastLuaStatus,
            long expectedDailyUsage,
            long expectedMonthlySharedUsage
    ) throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 10L, 100L);
        setGlobalPolicy(POLICY_REPEAT_BLOCK, repeatActive);
        setGlobalPolicy(POLICY_IMMEDIATE_BLOCK, immediateActive);
        setGlobalPolicy(POLICY_LINE_LIMIT_SHARED, sharedActive);
        setGlobalPolicy(POLICY_LINE_LIMIT_DAILY, dailyActive);
        setGlobalPolicy(POLICY_APP_DATA, appActive);
        setGlobalPolicy(POLICY_APP_WHITELIST, whitelistActive);
        setRepeatBlockForNow(lineId);
        setImmediateBlock(lineId);
        setDailyTotalLimit(lineId, 20L);
        setAppDailyLimit(lineId, appId, 30L);
        setMonthlySharedLimit(lineId, 20L);
        setAppWhitelist(lineId, appId);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        assertDoneLog(
                traceId,
                expectedIndividualBytes,
                expectedSharedBytes,
                0L,
                expectedRemainingBytes,
                expectedFinalStatus,
                expectedLastLuaStatus
        );
        assertRedisState(
                lineId,
                familyId,
                appId,
                10L - expectedIndividualBytes,
                100L - expectedSharedBytes,
                expectedDailyUsage,
                expectedDailyUsage,
                expectedMonthlySharedUsage
        );
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 정책 matrix 테스트가 소비할 64개 전역 flag 조합을 생성합니다.
     */
    private static Stream<Arguments> policyMatrixCases() {
        return IntStream.range(0, 64)
                .mapToObj(TrafficDataPolicyAcceptanceTest::policyMatrixCase);
    }

    /**
     * bit mask 하나를 정책 flag 조합과 해당 조합의 기대 결과로 변환합니다.
     */
    private static Arguments policyMatrixCase(int mask) {
        boolean repeatActive = (mask & 1) != 0;
        boolean immediateActive = (mask & 2) != 0;
        boolean sharedActive = (mask & 4) != 0;
        boolean dailyActive = (mask & 8) != 0;
        boolean appActive = (mask & 16) != 0;
        boolean whitelistActive = (mask & 32) != 0;
        String caseName = "repeat=" + repeatActive
                + ", immediate=" + immediateActive
                + ", shared=" + sharedActive
                + ", daily=" + dailyActive
                + ", app=" + appActive
                + ", whitelist=" + whitelistActive;

        if (whitelistActive) {
            return Arguments.of(caseName, repeatActive, immediateActive, sharedActive, dailyActive, appActive, true,
                    10L, 40L, 0L, "SUCCESS", "OK", 50L, 40L);
        }
        if (immediateActive) {
            return Arguments.of(caseName, repeatActive, true, sharedActive, dailyActive, appActive, false,
                    0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_IMMEDIATE", 0L, 0L);
        }
        if (repeatActive) {
            return Arguments.of(caseName, true, false, sharedActive, dailyActive, appActive, false,
                    0L, 0L, 50L, "NOT_DEDUCTED", "BLOCKED_REPEAT", 0L, 0L);
        }
        if (dailyActive) {
            return Arguments.of(caseName, false, false, sharedActive, true, appActive, false,
                    10L, 10L, 30L, "PARTIAL_SUCCESS", "HIT_DAILY_LIMIT", 20L, 10L);
        }
        if (appActive) {
            return Arguments.of(caseName, false, false, sharedActive, false, true, false,
                    10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_APP_DAILY_LIMIT", 30L, 20L);
        }
        if (sharedActive) {
            return Arguments.of(caseName, false, false, true, false, false, false,
                    10L, 20L, 20L, "PARTIAL_SUCCESS", "HIT_MONTHLY_SHARED_LIMIT", 30L, 20L);
        }
        return Arguments.of(caseName, false, false, false, false, false, false,
                10L, 40L, 0L, "SUCCESS", "OK", 50L, 40L);
    }

    /**
     * daily/shared/app limit flag 조합별로 처리량과 최종 상태가 기대값에 맞는지 검증합니다.
     */
    private void assertLimitFlagCombination(
            boolean dailyActive,
            boolean sharedActive,
            boolean appActive,
            long expectedIndividualBytes,
            long expectedSharedBytes,
            long expectedMonthlySharedUsage,
            String expectedFinalStatus,
            String expectedLastLuaStatus,
            long expectedDailyUsage
    ) throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, 10L, 100L);
        setDailyTotalLimit(lineId, dailyActive ? 40L : -1L);
        setMonthlySharedLimit(lineId, sharedActive ? 20L : -1L);
        setAppDailyLimit(lineId, appId, appActive ? 30L : -1L);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        long expectedRemaining = 50L - expectedIndividualBytes - expectedSharedBytes;
        assertDoneLog(
                traceId,
                expectedIndividualBytes,
                expectedSharedBytes,
                0L,
                expectedRemaining,
                expectedFinalStatus,
                expectedLastLuaStatus
        );
        assertRedisState(
                lineId,
                familyId,
                appId,
                10L - expectedIndividualBytes,
                100L - expectedSharedBytes,
                expectedDailyUsage,
                expectedDailyUsage,
                expectedMonthlySharedUsage
        );
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 여러 limit이 동시에 존재할 때 정책 우선순위에 따라 선택되는 첫 제한 상태를 검증합니다.
     */
    private void assertPriorityScenario(
            long individualAmount,
            long sharedAmount,
            long dailyLimit,
            long appLimit,
            long monthlySharedLimit,
            long expectedIndividualBytes,
            long expectedSharedBytes,
            String expectedLastLuaStatus
    ) throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();
        preparePolicyScenario(lineId, familyId, individualAmount, sharedAmount);
        setDailyTotalLimit(lineId, dailyLimit);
        setAppDailyLimit(lineId, appId, appLimit);
        setMonthlySharedLimit(lineId, monthlySharedLimit);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        long expectedTotal = expectedIndividualBytes + expectedSharedBytes;
        assertDoneLog(
                traceId,
                expectedIndividualBytes,
                expectedSharedBytes,
                0L,
                50L - expectedTotal,
                "PARTIAL_SUCCESS",
                expectedLastLuaStatus
        );
        assertRedisState(
                lineId,
                familyId,
                appId,
                individualAmount - expectedIndividualBytes,
                sharedAmount - expectedSharedBytes,
                expectedTotal,
                expectedTotal,
                expectedSharedBytes
        );
        assertRdbSourcesUnchanged(lineId, familyId);
    }

    /**
     * 정책 테스트가 필요한 RDB source, Redis balance, line 정책 준비 완료 marker를 한 번에 구성합니다.
     */
    private void preparePolicyScenario(long lineId, long familyId, long individualAmount, long sharedAmount) {
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, individualAmount);
        putSharedBalance(familyId, sharedAmount);
        markLinePolicyReady(lineId);
    }

    /**
     * 특정 전역 정책 flag를 Redis snapshot에 직접 기록해 정책 활성/비활성 조합을 만듭니다.
     */
    private void setGlobalPolicy(int policyId, boolean active) {
        String policyKey = trafficRedisKeyFactory.policyKey(policyId);
        cacheStringRedisTemplate.opsForHash().put(policyKey, "value", active ? "1" : "0");
        cacheStringRedisTemplate.opsForHash().put(policyKey, "version", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * line daily total limit snapshot을 Redis에 설정합니다.
     */
    private void setDailyTotalLimit(long lineId, long limit) {
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.dailyTotalLimitKey(lineId), "value", String.valueOf(limit));
    }

    /**
     * line monthly shared limit snapshot을 Redis에 설정합니다.
     */
    private void setMonthlySharedLimit(long lineId, long limit) {
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.monthlySharedLimitKey(lineId), "value", String.valueOf(limit));
    }

    /**
     * 기존 monthly shared usage가 있는 limit 소진 시나리오를 만들기 위해 현재 월 counter를 설정합니다.
     */
    private void setMonthlySharedUsage(long lineId, long usage) {
        YearMonth currentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForValue()
                .set(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, currentMonth), String.valueOf(usage));
    }

    /**
     * 앱별 daily data limit snapshot을 Redis hash field에 설정합니다.
     */
    private void setAppDailyLimit(long lineId, int appId, long limit) {
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.appDataDailyLimitKey(lineId), "limit:" + appId, String.valueOf(limit));
    }

    /**
     * 앱별 speed limit snapshot을 Redis hash field에 설정합니다.
     */
    private void setAppSpeedLimit(long lineId, int appId, int speedBytesPerSecond) {
        cacheStringRedisTemplate.opsForHash()
                .put(
                        trafficRedisKeyFactory.appSpeedLimitKey(lineId),
                        "speed:" + appId,
                        String.valueOf(speedBytesPerSecond)
                );
    }

    /**
     * 기존 예약 값이 현재 테스트의 완료 예정 시각 계산에 영향을 주지 않도록 제거합니다.
     */
    private void deleteAppSpeedReservation(long lineId, int appId) {
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.qosSpeedLimitNextAvailableKey(lineId, appId));
    }

    /**
     * 앱 속도 제한 예약 키의 epoch millis 값을 읽습니다.
     */
    private long readAppSpeedReservation(long lineId, int appId) {
        String value = cacheStringRedisTemplate.opsForValue()
                .get(trafficRedisKeyFactory.qosSpeedLimitNextAvailableKey(lineId, appId));
        assertThat(value).isNotBlank();
        return Long.parseLong(value);
    }

    /**
     * 기존 daily total usage가 있는 limit 소진 시나리오를 만들기 위해 현재 날짜 counter를 설정합니다.
     */
    private void setDailyTotalUsage(long lineId, long usage) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForValue()
                .set(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, today), String.valueOf(usage));
    }

    /**
     * 기존 앱별 daily usage가 있는 limit 소진 시나리오를 만들기 위해 현재 날짜 hash field를 설정합니다.
     */
    private void setDailyAppUsage(long lineId, int appId, long usage) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.dailyAppUsageKey(lineId, today), "app:" + appId, String.valueOf(usage));
    }

    /**
     * line 정책 snapshot이 준비된 것으로 표시해 hydrate 경로 대신 테스트가 설정한 Redis 정책을 사용하게 합니다.
     */
    private void markLinePolicyReady(long lineId) {
        cacheStringRedisTemplate.opsForValue().set(trafficRedisKeyFactory.linePolicyReadyKey(lineId), "1");
    }

    /**
     * 현재 시점에 유효한 즉시 차단 정책 snapshot을 Redis에 설정합니다.
     */
    private void setImmediateBlock(long lineId) {
        long blockEndEpochSecond = LocalDate.now(trafficRedisRuntimePolicy.zoneId())
                .plusDays(1)
                .atStartOfDay(trafficRedisRuntimePolicy.zoneId())
                .toEpochSecond();
        cacheStringRedisTemplate.opsForHash()
                .put(trafficRedisKeyFactory.immediatelyBlockEndKey(lineId), "value", String.valueOf(blockEndEpochSecond));
    }

    /**
     * 현재 요일/시간을 포함하는 반복 차단 정책 snapshot을 Redis에 설정합니다.
     */
    private void setRepeatBlockForNow(long lineId) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        LocalTime now = LocalTime.now(trafficRedisRuntimePolicy.zoneId());
        int dayNum = today.getDayOfWeek().getValue() % 7;
        int nowSecond = now.toSecondOfDay();
        int startSecond = Math.max(0, nowSecond - 60);
        int endSecond = Math.min(86_399, nowSecond + 60);
        cacheStringRedisTemplate.opsForHash().put(
                trafficRedisKeyFactory.repeatBlockKey(lineId),
                "day:" + dayNum + ":acceptance",
                startSecond + ":" + endSecond
        );
    }

    /**
     * 특정 앱을 line whitelist set에 추가해 정책 우회 시나리오를 만듭니다.
     */
    private void setAppWhitelist(long lineId, int appId) {
        cacheStringRedisTemplate.opsForSet()
                .add(trafficRedisKeyFactory.appWhitelistKey(lineId), String.valueOf(appId));
    }

    /**
     * 정책 적용 후 Redis 잔량과 usage counter가 기대값으로 정리됐는지 검증합니다.
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
     * 정책 차감이 RDB hydrate source 값을 직접 변경하지 않았음을 검증합니다.
     */
    private void assertRdbSourcesUnchanged(long lineId, long familyId) {
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
    }
}
