package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.traffic.domain.dto.response.TrafficGenerateResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

/**
 * 트래픽 처리 파이프라인의 로컬 인수 테스트입니다.
 *
 * <p>중요 제약:
 * - 반드시 local 프로파일에서만 실행합니다.
 * - CI 환경에서는 실행하지 않습니다.
 * - 각 테스트 시작 전 Redis flushall + DB 초기화(가족/회선 잔량)를 강제합니다.
 */
@Tag("local-only")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisabledIfEnvironmentVariable(named = "CI", matches = "(?i)true")
class TrafficFlowLocalAcceptanceTest {

    private static final long FAMILY_ID = 1L;
    private static final long LINE_ID = 1L;
    private static final int APP_ID = 1;
    private static final long RESET_FAMILY_REMAINING = 100L;
    private static final long RESET_LINE_REMAINING = 200L;
    private static final String TARGET_LINE_IDS = "1,2,3,4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    @Qualifier("cacheStringRedisTemplate")
    private StringRedisTemplate cacheStringRedisTemplate;

    @Autowired
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Autowired
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Autowired
    private TrafficPolicyBootstrapService trafficPolicyBootstrapService;

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장을 위해 cache Redis를 매번 비운다.
        // streams Redis를 flushall 하면 stream key/group/PEL이 함께 사라져
        // local consumer 루프가 NOGROUP 상태에 빠질 수 있으므로 여기서는 건드리지 않는다.
        flushAll(cacheStringRedisTemplate);

        // 시나리오 기준 대상 데이터(가족 1, 회선 1~4)가 존재하는지 먼저 검증한다.
        Integer familyExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM FAMILY WHERE family_id = ? AND deleted_at IS NULL",
                Integer.class,
                FAMILY_ID
        );
        assertThat(familyExists).isNotNull();
        assertThat(familyExists).isEqualTo(1);

        Integer lineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM LINE WHERE line_id IN (1,2,3,4) AND deleted_at IS NULL",
                Integer.class
        );
        assertThat(lineCount).isNotNull();
        assertThat(lineCount).isEqualTo(4);

        // 공통 초기값: family 잔량 100, line(1~4) 잔량 200.
        jdbcTemplate.update(
                "UPDATE FAMILY SET pool_remaining_data = ?, pool_total_data = ?, updated_at = NOW(6) WHERE family_id = ?",
                RESET_FAMILY_REMAINING,
                RESET_FAMILY_REMAINING,
                FAMILY_ID
        );
        jdbcTemplate.update(
                "UPDATE LINE SET remaining_data = ?, updated_at = NOW(6) WHERE line_id IN (" + TARGET_LINE_IDS + ")",
                RESET_LINE_REMAINING
        );

        // 1) lineId 1~4 즉시 차단 값(block_end_at)을 null로 초기화한다.
        jdbcTemplate.update("UPDATE LINE SET block_end_at = NULL, updated_at = NOW(6) WHERE line_id IN (" + TARGET_LINE_IDS + ")");

        // 2) lineId 1~4의 모든 repeat_block / repeat_block_day 레코드를 삭제한다.
        jdbcTemplate.update(
                "DELETE rbd FROM REPEAT_BLOCK_DAY rbd JOIN REPEAT_BLOCK rb ON rb.repeat_block_id = rbd.repeat_block_id WHERE rb.line_id IN (" + TARGET_LINE_IDS + ")"
        );
        jdbcTemplate.update("DELETE FROM REPEAT_BLOCK WHERE line_id IN (" + TARGET_LINE_IDS + ")");

        // 3) lineId 1~4의 모든 앱별 제한 정책(APP_POLICY)을 삭제한다.
        jdbcTemplate.update("DELETE FROM APP_POLICY WHERE line_id IN (" + TARGET_LINE_IDS + ")");

        // 4) lineId 1~4의 모든 일별 사용 한도 정책(LINE_LIMIT daily)을 삭제한다.
        jdbcTemplate.update(
                "DELETE FROM LINE_LIMIT " +
                        "WHERE line_id IN (" + TARGET_LINE_IDS + ") " +
                        "AND (is_daily_limit_active = 1 OR daily_data_limit <> -1)"
        );

        // 5) lineId 1~4의 모든 월별 공유풀 사용 한도 정책(LINE_LIMIT shared)을 삭제한다.
        jdbcTemplate.update(
                "DELETE FROM LINE_LIMIT " +
                        "WHERE line_id IN (" + TARGET_LINE_IDS + ") " +
                        "AND (is_shared_limit_active = 1 OR shared_data_limit <> -1)"
        );

        // 전역 정책 1~7은 기본 활성 상태로 복구한다.
        jdbcTemplate.update(
                "UPDATE POLICY SET is_active = 1, updated_at = NOW(6) WHERE policy_id BETWEEN 1 AND 7 AND deleted_at IS NULL"
        );

        // cache flushall 직후에는 policy:* 키가 사라지므로 bootstrap 동기화를 즉시 재실행한다.
        trafficPolicyBootstrapService.reconcilePolicyActivationSnapshot();
    }

    @Test
    @DisplayName("기본 흐름: 개인풀 차감 요청은 DB 잔량에서 refill 후 정상 차감된다")
    void shouldDeductIndividualBalanceAfterHydrateAndRefill() throws Exception {
        long before = readLineRemaining(LINE_ID);

        String traceId = enqueueTrafficRequest(LINE_ID, FAMILY_ID, APP_ID, 50L);

        await("line remaining deducted by request", () -> readLineRemaining(LINE_ID) == before - 50L);
        TrafficDeductDoneLog doneLog = awaitDoneLog(traceId);

        long after = readLineRemaining(LINE_ID);
        assertThat(after).isEqualTo(before - 50L);
        assertThat(doneLog.getTraceId()).isEqualTo(traceId);
        assertThat(doneLog.getLineId()).isEqualTo(LINE_ID);
        assertThat(doneLog.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(doneLog.getAppId()).isEqualTo(APP_ID);
        assertThat(doneLog.getApiTotalData()).isEqualTo(50L);
        assertThat(doneLog.getDeductedTotalBytes()).isEqualTo(50L);
        assertThat(doneLog.getApiRemainingData()).isEqualTo(0L);
        assertThat(doneLog.getFinalStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("즉시 차단 정책 활성 + line block 설정 시 차감이 발생하지 않는다")
    void shouldBlockWhenImmediatePolicyIsActive() throws Exception {
        jdbcTemplate.update(
                "UPDATE LINE SET block_end_at = DATE_ADD(NOW(6), INTERVAL 10 MINUTE), updated_at = NOW(6) WHERE line_id = ?",
                LINE_ID
        );
        trafficPolicyBootstrapService.reconcilePolicyActivationSnapshot();

        long before = readLineRemaining(LINE_ID);
        String traceId = enqueueTrafficRequest(LINE_ID, FAMILY_ID, APP_ID, 50L);
        TrafficDeductDoneLog doneLog = awaitDoneLog(traceId);

        // 차감이 없어야 하므로 짧게 대기 후 동일 값 유지 여부를 확인한다.
        TimeUnit.MILLISECONDS.sleep(800);
        long after = readLineRemaining(LINE_ID);
        assertThat(after).isEqualTo(before);
        assertThat(doneLog.getDeductedTotalBytes()).isEqualTo(0L);
        assertThat(doneLog.getApiRemainingData()).isEqualTo(50L);
        assertThat(doneLog.getLastLuaStatus()).isEqualTo("BLOCKED_IMMEDIATE");
    }

    @Test
    @DisplayName("즉시 차단 정책 비활성 시 line block이 있어도 차감이 진행된다")
    void shouldBypassImmediateBlockWhenPolicyIsDisabled() throws Exception {
        jdbcTemplate.update(
                "UPDATE LINE SET block_end_at = DATE_ADD(NOW(6), INTERVAL 10 MINUTE), updated_at = NOW(6) WHERE line_id = ?",
                LINE_ID
        );
        jdbcTemplate.update(
                "UPDATE POLICY SET is_active = 0, updated_at = NOW(6) WHERE policy_id = 2 AND deleted_at IS NULL"
        );
        trafficPolicyBootstrapService.reconcilePolicyActivationSnapshot();

        long before = readLineRemaining(LINE_ID);
        String traceId = enqueueTrafficRequest(LINE_ID, FAMILY_ID, APP_ID, 50L);

        await("line remaining deducted when immediate policy disabled", () -> readLineRemaining(LINE_ID) == before - 50L);
        TrafficDeductDoneLog doneLog = awaitDoneLog(traceId);

        long after = readLineRemaining(LINE_ID);
        assertThat(after).isEqualTo(before - 50L);
        assertThat(doneLog.getDeductedTotalBytes()).isEqualTo(50L);
        assertThat(doneLog.getApiRemainingData()).isEqualTo(0L);
        assertThat(doneLog.getFinalStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("일일 한도 정책 활성 시 한도까지만 차감되고 정책 비활성 시 전체 차감된다")
    void shouldRespectDailyLimitOnlyWhenPolicyIsActive() throws Exception {
        // [단계 1] 특정 회선(LINE_1)에 대해 '일일 사용 한도'를 30으로 설정합니다.
        // 이 시점에서는 'is_daily_limit_active'가 1이므로 한도 제한이 적용되어야 합니다.
        jdbcTemplate.update(
                """
                INSERT INTO LINE_LIMIT (
                    line_id,
                    daily_data_limit,
                    is_daily_limit_active,
                    shared_data_limit,
                    is_shared_limit_active,
                    created_at
                ) VALUES (?, 30, 1, -1, 0, NOW(6))
                """,
                LINE_ID
        );

        // 변경된 DB 정책 설정을 Redis 스냅샷에 반영합니다.
        trafficPolicyBootstrapService.reconcilePolicyActivationSnapshot();

        // 검증을 위해 현재 시점의 Redis 키 이름을 생성합니다.
        YearMonth currentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        LocalDate currentDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        String indivBalanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(LINE_ID, currentMonth);
        String dailyUsageKey = trafficRedisKeyFactory.dailyTotalUsageKey(LINE_ID, currentDate);

        // [단계 2] 한도(30)보다 큰 50만큼의 트래픽 차감을 요청합니다.
        String firstTraceId = enqueueTrafficRequest(LINE_ID, FAMILY_ID, APP_ID, 50L);
        TrafficDeductDoneLog firstDoneLog = awaitDoneLog(firstTraceId);

        // 일일 한도 정책이 활성화되어 있으므로, 요청한 50이 아닌 한도인 '30'만 차감되어야 합니다.
        await("daily usage reflects active daily limit(30)", () -> readStringValue(dailyUsageKey).equals("30"));
        assertThat(readHashField(indivBalanceKey, "amount")).isEqualTo("20"); // 원래 50 요청 중 30만 차감되었으므로 (나머지 20은 차감되지 않음)
        assertThat(firstDoneLog.getDeductedTotalBytes()).isEqualTo(30L);
        assertThat(firstDoneLog.getApiRemainingData()).isEqualTo(20L);
        assertThat(firstDoneLog.getLastLuaStatus()).isEqualTo("HIT_DAILY_LIMIT");

        // [단계 3] 이제 전역 '일일 총량 제한' 정책(policy_id = 4) 자체를 비활성화해봅니다.
        // 회선별 한도 설정이 남아있더라도, 전역 마스터 스위치가 꺼지면 한도 제한이 무시되어야 합니다.
        jdbcTemplate.update(
                "UPDATE POLICY SET is_active = 0, updated_at = NOW(6) WHERE policy_id = 4 AND deleted_at IS NULL"
        );
        trafficPolicyBootstrapService.reconcilePolicyActivationSnapshot();

        // [단계 4] 다시 동일하게 50만큼의 트래픽 차감을 요청합니다.
        String secondTraceId = enqueueTrafficRequest(LINE_ID, FAMILY_ID, APP_ID, 50L);
        TrafficDeductDoneLog secondDoneLog = awaitDoneLog(secondTraceId);

        // 이번에는 전역 정책이 꺼져 있으므로, 한도에 걸리지 않고 요청한 '50'이 모두 정상적으로 차감되어야 합니다.
        // 결과적으로 누적 사용량은 30(이전) + 50(현재) = 80이 됩니다.
        await("daily usage adds full request when daily policy disabled", () -> readStringValue(dailyUsageKey).equals("80"));
        assertThat(secondDoneLog.getDeductedTotalBytes()).isEqualTo(50L);
        assertThat(secondDoneLog.getApiRemainingData()).isEqualTo(0L);
        assertThat(secondDoneLog.getFinalStatus()).isEqualTo("SUCCESS");
    }

    private String enqueueTrafficRequest(long lineId, long familyId, int appId, long apiTotalData) throws Exception {
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

    private long readLineRemaining(long lineId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT remaining_data FROM LINE WHERE line_id = ? AND deleted_at IS NULL",
                Long.class,
                lineId
        );
        assertThat(value).isNotNull();
        return value;
    }

    private String readHashField(String key, String field) {
        Object value = cacheStringRedisTemplate.opsForHash().get(key, field);
        return value == null ? "" : String.valueOf(value);
    }

    private String readStringValue(String key) {
        String value = cacheStringRedisTemplate.opsForValue().get(key);
        return value == null ? "" : value;
    }

    private TrafficDeductDoneLog findDoneLog(String traceId) {
        Query query = Query.query(Criteria.where("trace_id").is(traceId));
        return mongoTemplate.findOne(query, TrafficDeductDoneLog.class);
    }

    private TrafficDeductDoneLog awaitDoneLog(String traceId) throws Exception {
        long startedAt = System.currentTimeMillis();
        long timeoutMs = 5_000L;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            TrafficDeductDoneLog doneLog = findDoneLog(traceId);
            if (doneLog != null) {
                return doneLog;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timeout while waiting done log: traceId=" + traceId);
    }

    private void await(String description, BooleanSupplier condition) throws Exception {
        long startedAt = System.currentTimeMillis();
        long timeoutMs = 5_000L;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Timeout while waiting: " + description);
    }

    private void flushAll(StringRedisTemplate redisTemplate) {
        redisTemplate.execute((RedisCallback<String>) connection -> {
            try {
                connection.serverCommands().flushAll();
            } catch (DataAccessException e) {
                throw e;
            }
            return "OK";
        });
    }
}
