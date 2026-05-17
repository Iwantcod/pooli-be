package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.traffic.domain.dto.response.TrafficGenerateResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.service.invoke.TrafficStreamInfraService;
import com.pooli.traffic.service.invoke.TrafficStreamConsumerRunner;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@Tag("local-only")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.traffic.outbox.retry.fixed-delay-ms=600000",
                "app.streams.key-traffic-request=traffic:deduct:request:acceptance",
                "app.streams.group-traffic=traffic-deduct-acceptance-cg",
                "app.streams.consumer-name=traffic-deduct-acceptance-consumer",
                "app.streams.worker-thread-count=4",
                "app.streams.worker-queue-capacity=32",
                "app.streams.read-count=8",
                "app.streams.block-ms=100",
                "app.streams.key-traffic-dlq=traffic:deduct:dlq:acceptance"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisabledIfEnvironmentVariable(named = "CI", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TrafficAcceptanceTestSupport {

    protected static final long FAMILY_ID_1 = 1L;
    protected static final long FAMILY_ID_2 = 2L;
    protected static final long FAMILY_ID_3 = 3L;
    protected static final long LINE_ID_1 = 1L;
    protected static final long LINE_ID_12 = 12L;
    protected static final long DEFAULT_INDIVIDUAL_SOURCE_BYTES = 200L;
    protected static final long DEFAULT_SHARED_SOURCE_BYTES = 100L;

    private static final int POLICY_REPEAT_BLOCK = 1;
    private static final int POLICY_IMMEDIATE_BLOCK = 2;
    private static final int POLICY_LINE_LIMIT_SHARED = 3;
    private static final int POLICY_LINE_LIMIT_DAILY = 4;
    private static final int POLICY_APP_DATA = 5;
    private static final int POLICY_APP_SPEED = 6;
    private static final int POLICY_APP_WHITELIST = 7;
    private static final Set<Integer> TEST_POLICY_IDS = Set.of(
            POLICY_REPEAT_BLOCK,
            POLICY_IMMEDIATE_BLOCK,
            POLICY_LINE_LIMIT_SHARED,
            POLICY_LINE_LIMIT_DAILY,
            POLICY_APP_DATA,
            POLICY_APP_SPEED,
            POLICY_APP_WHITELIST
    );

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Autowired
    protected TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Autowired
    protected AppStreamsProperties appStreamsProperties;

    @Autowired
    protected TrafficStreamInfraService trafficStreamInfraService;

    @Autowired
    protected TrafficStreamConsumerRunner trafficStreamConsumerRunner;

    @Autowired
    @Qualifier("cacheStringRedisTemplate")
    protected StringRedisTemplate cacheStringRedisTemplate;

    @Autowired
    @Qualifier("streamsStringRedisTemplate")
    protected StringRedisTemplate streamsStringRedisTemplate;

    protected FixtureIds fixtureIds;

    /**
     * 각 acceptance 테스트 시작 전에 DB/Redis/Stream fixture를 같은 시작 상태로 맞춥니다.
     */
    @BeforeEach
    void resetAcceptanceFixture() {
        fixtureIds = loadFixtureIds();
        resetDatabaseFixture();
        resetCacheRedisFixture();
        resetStreamsRedisFixture();
        prepareGlobalPolicySnapshot(true);
    }

    /**
     * 테스트가 직접 만든 전역 정책 Redis snapshot을 제거해 다음 테스트의 정책 상태를 오염시키지 않습니다.
     */
    @AfterEach
    void cleanupAcceptanceFixture() {
        deleteGlobalPolicySnapshot();
    }

    /**
     * 클래스 단위 테스트가 끝난 뒤 consumer를 멈추고 acceptance 전용 stream key를 제거합니다.
     */
    @AfterAll
    void cleanupAcceptanceStreams() {
        if (trafficStreamConsumerRunner.isRunning()) {
            trafficStreamConsumerRunner.stop();
        }
        deleteStreamsKey(appStreamsProperties.getKeyTrafficRequest());
        deleteStreamsKey(appStreamsProperties.getKeyTrafficDlq());
    }

    /**
     * 개인풀 hydrate source인 LINE.total_data를 원하는 값으로 고정합니다.
     */
    protected void setLineSourceTotalData(long lineId, long amount) {
        int updatedRows = jdbcTemplate.update(
                """
                UPDATE LINE
                SET total_data = ?,
                    last_balance_refreshed_at = STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'),
                    block_end_at = NULL,
                    deleted_at = NULL,
                    updated_at = NOW(6)
                WHERE line_id = ?
                """,
                amount,
                lineId
        );
        assertThat(updatedRows).isEqualTo(1);
    }

    /**
     * 공유풀 hydrate source인 FAMILY.pool_total_data를 원하는 값으로 고정합니다.
     */
    protected void setFamilySourcePoolTotalData(long familyId, long amount) {
        int updatedRows = jdbcTemplate.update(
                """
                UPDATE FAMILY
                SET pool_total_data = ?,
                    last_balance_refreshed_at = STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'),
                    deleted_at = NULL,
                    updated_at = NOW(6)
                WHERE family_id = ?
                """,
                amount,
                familyId
        );
        assertThat(updatedRows).isEqualTo(1);
    }

    /**
     * Redis-only 차감 중 RDB 개인풀 source 값이 변하지 않았는지 확인할 때 사용합니다.
     */
    protected long readLineSourceTotalData(long lineId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT total_data FROM LINE WHERE line_id = ? AND deleted_at IS NULL",
                Long.class,
                lineId
        );
        assertThat(value).isNotNull();
        return value;
    }

    /**
     * Redis-only 차감 중 RDB 공유풀 source 값이 변하지 않았는지 확인할 때 사용합니다.
     */
    protected long readFamilySourcePoolTotalData(long familyId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT pool_total_data FROM FAMILY WHERE family_id = ? AND deleted_at IS NULL",
                Long.class,
                familyId
        );
        assertThat(value).isNotNull();
        return value;
    }

    /**
     * hydrate 경로를 거치지 않는 시나리오에서 개인풀 Redis balance hash를 직접 준비합니다.
     */
    protected void putIndividualBalance(long lineId, long amount) {
        YearMonth currentMonth = currentMonth();
        String balanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(lineId, currentMonth);
        cacheStringRedisTemplate.opsForHash().putAll(balanceKey, Map.of("amount", String.valueOf(amount), "qos", "0"));
    }

    /**
     * hydrate 경로를 거치지 않는 시나리오에서 공유풀 Redis balance hash를 직접 준비합니다.
     */
    protected void putSharedBalance(long familyId, long amount) {
        YearMonth currentMonth = currentMonth();
        String balanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, currentMonth);
        cacheStringRedisTemplate.opsForHash().put(balanceKey, "amount", String.valueOf(amount));
    }

    /**
     * 현재 월 개인풀 Redis balance hash의 amount 필드를 읽습니다.
     */
    protected long readIndividualBalanceAmount(long lineId) {
        return readHashAmount(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, currentMonth()));
    }

    /**
     * 현재 월 공유풀 Redis balance hash의 amount 필드를 읽습니다.
     */
    protected long readSharedBalanceAmount(long familyId) {
        return readHashAmount(trafficRedisKeyFactory.remainingSharedAmountKey(familyId, currentMonth()));
    }

    /**
     * 오늘 날짜 기준 line daily total usage counter를 읽습니다.
     */
    protected long readDailyTotalUsage(long lineId) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        return readLongValue(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, today));
    }

    /**
     * 오늘 날짜 기준 line/app daily usage hash field를 읽습니다.
     */
    protected long readDailyAppUsage(long lineId, int appId) {
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        String usageKey = trafficRedisKeyFactory.dailyAppUsageKey(lineId, today);
        return readHashLong(usageKey, "app:" + appId);
    }

    /**
     * 현재 월 기준 line monthly shared usage counter를 읽습니다.
     */
    protected long readMonthlySharedUsage(long lineId) {
        return readLongValue(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, currentMonth()));
    }

    /**
     * DB POLICY를 건드리지 않고 차감 Lua가 읽는 전역 정책 Redis snapshot만 준비합니다.
     */
    protected void prepareGlobalPolicySnapshot(boolean active) {
        long version = System.currentTimeMillis();
        for (Integer policyId : TEST_POLICY_IDS) {
            String policyKey = trafficRedisKeyFactory.policyKey(policyId);
            cacheStringRedisTemplate.opsForHash().put(policyKey, "value", active ? "1" : "0");
            cacheStringRedisTemplate.opsForHash().put(policyKey, "version", String.valueOf(version));
        }
        cacheStringRedisTemplate.opsForValue().set(trafficRedisKeyFactory.policyBootstrapVersionKey(), String.valueOf(version));
    }

    /**
     * 실제 API 엔드포인트로 트래픽 요청을 넣고 response traceId를 반환합니다.
     */
    protected String enqueueTrafficRequest(long lineId, long familyId, int appId, long apiTotalData) throws Exception {
        String requestBody = """
                {
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": %d
                }
                """.formatted(lineId, familyId, appId, apiTotalData);

        MvcResult mvcResult = mockMvc.perform(
                        post("/api/traffic/requests")
                                .contentType("application/json")
                                .content(requestBody.getBytes(StandardCharsets.UTF_8))
                )
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        TrafficGenerateResDto response = objectMapper.readValue(responseBody, TrafficGenerateResDto.class);
        assertThat(response.getTraceId()).isNotBlank();
        return response.getTraceId();
    }

    /**
     * done log가 생성될 때까지 기다린 뒤 source별 차감량과 최종 상태를 한 번에 검증합니다.
     */
    protected TrafficDeductDoneLog assertDoneLog(
            String traceId,
            long expectedIndividualBytes,
            long expectedSharedBytes,
            long expectedQosBytes,
            long expectedRemainingBytes,
            String expectedFinalStatus,
            String expectedLastLuaStatus
    ) throws Exception {
        TrafficDeductDoneLog doneLog = awaitDoneLog(traceId);
        assertThat(doneLog.getTraceId()).isEqualTo(traceId);
        assertThat(doneLog.getDeductedIndividualBytes()).isEqualTo(expectedIndividualBytes);
        assertThat(doneLog.getDeductedSharedBytes()).isEqualTo(expectedSharedBytes);
        assertThat(doneLog.getDeductedQosBytes()).isEqualTo(expectedQosBytes);
        assertThat(doneLog.getApiRemainingData()).isEqualTo(expectedRemainingBytes);
        assertThat(doneLog.getFinalStatus()).isEqualTo(expectedFinalStatus);
        assertThat(doneLog.getLastLuaStatus()).isEqualTo(expectedLastLuaStatus);
        return doneLog;
    }

    /**
     * 비동기 stream consumer가 done log를 저장할 때까지 polling합니다.
     */
    protected TrafficDeductDoneLog awaitDoneLog(String traceId) throws Exception {
        long startedAt = System.currentTimeMillis();
        long timeoutMs = 7_000L;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            TrafficDeductDoneLog doneLog = findDoneLog(traceId);
            if (doneLog != null) {
                return doneLog;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timeout while waiting done log: traceId=" + traceId);
    }

    /**
     * 비동기 Redis/DB side effect가 반영될 때까지 조건을 polling합니다.
     */
    protected void await(String description, BooleanSupplier condition) throws Exception {
        long startedAt = System.currentTimeMillis();
        long timeoutMs = 7_000L;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timeout while waiting: " + description);
    }

    /**
     * acceptance 테스트가 소유한 DB 데이터를 제거한 뒤 가족/회선 fixture를 다시 upsert합니다.
     */
    private void resetDatabaseFixture() {
        deleteDatabaseRowsOwnedByAcceptanceTest();
        upsertFamilies();
        upsertLinesAndFamilyLinks();
    }

    /**
     * line_id 1~12 범위에 연결된 정책/done-log/link 데이터를 삭제해 반복 실행 충돌을 막습니다.
     */
    private void deleteDatabaseRowsOwnedByAcceptanceTest() {
        jdbcTemplate.update("""
                DELETE rbd FROM REPEAT_BLOCK_DAY rbd
                JOIN REPEAT_BLOCK rb ON rb.repeat_block_id = rbd.repeat_block_id
                WHERE rb.line_id BETWEEN ? AND ?
                """, LINE_ID_1, LINE_ID_12);
        jdbcTemplate.update("DELETE FROM REPEAT_BLOCK WHERE line_id BETWEEN ? AND ?", LINE_ID_1, LINE_ID_12);
        jdbcTemplate.update("DELETE FROM APP_POLICY WHERE line_id BETWEEN ? AND ?", LINE_ID_1, LINE_ID_12);
        jdbcTemplate.update("DELETE FROM LINE_LIMIT WHERE line_id BETWEEN ? AND ?", LINE_ID_1, LINE_ID_12);
        jdbcTemplate.update("DELETE FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ? AND ?", LINE_ID_1, LINE_ID_12);
        jdbcTemplate.update("DELETE FROM TRAFFIC_REDIS_OUTBOX");
        jdbcTemplate.update("DELETE FROM FAMILY_LINE WHERE line_id BETWEEN ? AND ?", LINE_ID_1, LINE_ID_12);
    }

    /**
     * family_id 1~3을 acceptance 전용 공유풀 source fixture로 upsert합니다.
     */
    private void upsertFamilies() {
        for (long familyId = FAMILY_ID_1; familyId <= FAMILY_ID_3; familyId++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO FAMILY (
                        family_id,
                        pool_base_data,
                        pool_total_data,
                        family_threshold,
                        is_threshold_active,
                        last_balance_refreshed_at,
                        created_at,
                        updated_at
                    ) VALUES (?, 0, ?, 0, 0, STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'), NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE
                        pool_base_data = VALUES(pool_base_data),
                        pool_total_data = VALUES(pool_total_data),
                        family_threshold = VALUES(family_threshold),
                        is_threshold_active = VALUES(is_threshold_active),
                        last_balance_refreshed_at = VALUES(last_balance_refreshed_at),
                        deleted_at = NULL,
                        updated_at = NOW(6)
                    """,
                    familyId,
                    DEFAULT_SHARED_SOURCE_BYTES
            );
        }
    }

    /**
     * line_id 1~12와 FAMILY_LINE 매핑을 acceptance 전용 fixture로 upsert합니다.
     */
    private void upsertLinesAndFamilyLinks() {
        for (long lineId = LINE_ID_1; lineId <= LINE_ID_12; lineId++) {
            long familyId = resolveFamilyId(lineId);
            jdbcTemplate.update(
                    """
                    INSERT INTO LINE (
                        line_id,
                        user_id,
                        plan_id,
                        phone,
                        block_end_at,
                        total_data,
                        last_balance_refreshed_at,
                        is_main,
                        individual_threshold,
                        is_threshold_active,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, NULL, ?, STR_TO_DATE(DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01'), '%Y-%m-%d'), ?, 0, 0, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE
                        user_id = VALUES(user_id),
                        plan_id = VALUES(plan_id),
                        phone = VALUES(phone),
                        block_end_at = NULL,
                        total_data = VALUES(total_data),
                        last_balance_refreshed_at = VALUES(last_balance_refreshed_at),
                        is_main = VALUES(is_main),
                        individual_threshold = VALUES(individual_threshold),
                        is_threshold_active = VALUES(is_threshold_active),
                        deleted_at = NULL,
                        updated_at = NOW(6)
                    """,
                    lineId,
                    fixtureIds.userId(),
                    fixtureIds.planId(),
                    "010-9000-%04d".formatted(lineId),
                    DEFAULT_INDIVIDUAL_SOURCE_BYTES,
                    lineId == resolveOwnerLineId(familyId) ? 1 : 0
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO FAMILY_LINE (
                        family_id,
                        line_id,
                        role,
                        is_public,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, 1, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE
                        role = VALUES(role),
                        is_public = VALUES(is_public),
                        updated_at = NOW(6)
                    """,
                    familyId,
                    lineId,
                    lineId == resolveOwnerLineId(familyId) ? "OWNER" : "MEMBER"
            );
        }
    }

    /**
     * 각 테스트 family fixture에서 OWNER 역할을 맡는 대표 line id를 반환합니다.
     */
    private long resolveOwnerLineId(long familyId) {
        if (familyId == FAMILY_ID_1) {
            return 1L;
        }
        if (familyId == FAMILY_ID_2) {
            return 5L;
        }
        return 9L;
    }

    /**
     * line_id 범위 기준으로 acceptance fixture의 family_id를 결정합니다.
     */
    private long resolveFamilyId(long lineId) {
        if (lineId <= 4L) {
            return FAMILY_ID_1;
        }
        if (lineId <= 8L) {
            return FAMILY_ID_2;
        }
        return FAMILY_ID_3;
    }

    /**
     * 테스트 전용 row를 새로 만들지 않고 로컬 DB에 이미 있는 USERS/PLAN/APPLICATION id를 선택합니다.
     */
    private FixtureIds loadFixtureIds() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM USERS WHERE deleted_at IS NULL ORDER BY user_id LIMIT 1",
                Long.class
        );
        Integer planId = jdbcTemplate.queryForObject(
                "SELECT plan_id FROM PLAN WHERE deleted_at IS NULL ORDER BY plan_id LIMIT 1",
                Integer.class
        );
        Integer appId = jdbcTemplate.queryForObject(
                "SELECT application_id FROM APPLICATION ORDER BY application_id LIMIT 1",
                Integer.class
        );
        assertThat(userId).as("local USERS fixture must exist").isNotNull();
        assertThat(planId).as("local PLAN fixture must exist").isNotNull();
        assertThat(appId).as("local APPLICATION fixture must exist").isNotNull();
        return new FixtureIds(userId, planId, appId);
    }

    /**
     * cache Redis에서 acceptance fixture와 관련된 balance/policy/usage/lock key만 삭제합니다.
     */
    private void resetCacheRedisFixture() {
        List<String> keys = new ArrayList<>();
        YearMonth currentMonth = currentMonth();
        LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        for (long lineId = LINE_ID_1; lineId <= LINE_ID_12; lineId++) {
            keys.add(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, currentMonth));
            keys.add(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, today));
            keys.add(trafficRedisKeyFactory.dailyAppUsageKey(lineId, today));
            keys.add(trafficRedisKeyFactory.monthlySharedUsageKey(lineId, currentMonth));
            keys.add(trafficRedisKeyFactory.dailyTotalLimitKey(lineId));
            keys.add(trafficRedisKeyFactory.monthlySharedLimitKey(lineId));
            keys.add(trafficRedisKeyFactory.appDataDailyLimitKey(lineId));
            keys.add(trafficRedisKeyFactory.appSpeedLimitKey(lineId));
            keys.add(trafficRedisKeyFactory.appWhitelistKey(lineId));
            keys.add(trafficRedisKeyFactory.immediatelyBlockEndKey(lineId));
            keys.add(trafficRedisKeyFactory.repeatBlockKey(lineId));
            keys.add(trafficRedisKeyFactory.linePolicyReadyKey(lineId));
            keys.add(trafficRedisKeyFactory.linePolicyHydrateLockKey(lineId));
            keys.add(trafficRedisKeyFactory.indivHydrateLockKey(lineId));
            keys.add(trafficRedisKeyFactory.qosKey(lineId));
        }
        for (long familyId = FAMILY_ID_1; familyId <= FAMILY_ID_3; familyId++) {
            keys.add(trafficRedisKeyFactory.remainingSharedAmountKey(familyId, currentMonth));
            keys.add(trafficRedisKeyFactory.sharedHydrateLockKey(familyId));
            keys.add(trafficRedisKeyFactory.familyMetaKey(familyId));
        }
        cacheStringRedisTemplate.delete(keys);
    }

    /**
     * 전용 stream consumer group을 보장하고 이전 실행에서 남은 stream/DLQ 데이터를 비웁니다.
     */
    private void resetStreamsRedisFixture() {
        trafficStreamInfraService.ensureConsumerGroup();
        trimStream(appStreamsProperties.getKeyTrafficRequest());
        deleteStreamsKey(appStreamsProperties.getKeyTrafficDlq());
    }

    /**
     * acceptance 테스트가 준비한 전역 정책 Redis snapshot과 bootstrap lock/version key를 제거합니다.
     */
    private void deleteGlobalPolicySnapshot() {
        List<String> policyKeys = TEST_POLICY_IDS.stream()
                .map(policyId -> trafficRedisKeyFactory.policyKey(policyId))
                .toList();
        cacheStringRedisTemplate.delete(policyKeys);
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.policyBootstrapVersionKey());
        cacheStringRedisTemplate.delete(trafficRedisKeyFactory.policyBootstrapLockKey());
    }

    /**
     * streams Redis에서 지정한 key 하나를 삭제합니다.
     */
    private void deleteStreamsKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        streamsStringRedisTemplate.delete(key);
    }

    /**
     * consumer group은 유지하면서 stream record만 비워 다음 테스트가 같은 group을 재사용하게 합니다.
     */
    private void trimStream(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        streamsStringRedisTemplate.opsForStream().trim(key, 0);
    }

    /**
     * 트래픽 런타임 정책의 timezone 기준 현재 YearMonth를 반환합니다.
     */
    private YearMonth currentMonth() {
        return YearMonth.now(trafficRedisRuntimePolicy.zoneId());
    }

    /**
     * Redis balance hash에서 공통 amount 필드를 long으로 읽습니다.
     */
    private long readHashAmount(String key) {
        return readHashLong(key, "amount");
    }

    /**
     * Redis hash field를 long으로 읽고, 값이 없으면 테스트 편의를 위해 0으로 취급합니다.
     */
    private long readHashLong(String key, String field) {
        Object value = cacheStringRedisTemplate.opsForHash().get(key, field);
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * Redis string counter를 long으로 읽고, 값이 없으면 테스트 편의를 위해 0으로 취급합니다.
     */
    private long readLongValue(String key) {
        String value = cacheStringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    /**
     * traceId 기준 최신 TRAFFIC_DEDUCT_DONE row를 조회해 assertion용 domain 객체로 변환합니다.
     */
    private TrafficDeductDoneLog findDoneLog(String traceId) {
        List<TrafficDeductDoneLog> logs = jdbcTemplate.query(
                """
                SELECT
                    traffic_deduct_done_id,
                    trace_id,
                    record_id,
                    line_id,
                    family_id,
                    app_id,
                    api_total_data,
                    deducted_individual_bytes,
                    deducted_shared_bytes,
                    deducted_qos_bytes,
                    api_remaining_data,
                    final_status,
                    last_lua_status,
                    created_at,
                    started_at,
                    finished_at,
                    latency,
                    restore_status,
                    restore_status_updated_at,
                    restore_retry_count,
                    restore_last_error_message
                FROM TRAFFIC_DEDUCT_DONE
                WHERE trace_id = ?
                ORDER BY traffic_deduct_done_id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> TrafficDeductDoneLog.builder()
                        .trafficDeductDoneId(rs.getLong("traffic_deduct_done_id"))
                        .traceId(rs.getString("trace_id"))
                        .recordId(rs.getString("record_id"))
                        .lineId(rs.getLong("line_id"))
                        .familyId(rs.getLong("family_id"))
                        .appId(rs.getInt("app_id"))
                        .apiTotalData(rs.getLong("api_total_data"))
                        .deductedIndividualBytes(rs.getLong("deducted_individual_bytes"))
                        .deductedSharedBytes(rs.getLong("deducted_shared_bytes"))
                        .deductedQosBytes(rs.getLong("deducted_qos_bytes"))
                        .apiRemainingData(rs.getLong("api_remaining_data"))
                        .finalStatus(rs.getString("final_status"))
                        .lastLuaStatus(rs.getString("last_lua_status"))
                        .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                        .startedAt(rs.getTimestamp("started_at").toLocalDateTime())
                        .finishedAt(rs.getTimestamp("finished_at").toLocalDateTime())
                        .latency(rs.getObject("latency", Long.class))
                        .restoreStatus(rs.getString("restore_status"))
                        .restoreStatusUpdatedAt(rs.getTimestamp("restore_status_updated_at").toLocalDateTime())
                        .restoreRetryCount(rs.getInt("restore_retry_count"))
                        .restoreLastErrorMessage(rs.getString("restore_last_error_message"))
                        .build(),
                traceId
        );
        return logs.isEmpty() ? null : logs.getFirst();
    }

    protected record FixtureIds(long userId, int planId, int appId) {
    }
}
