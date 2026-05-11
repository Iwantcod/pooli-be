package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lua 정책/차감 스크립트 계약을 검증합니다.
 */
class TrafficLuaPolicyContractTest {

    private static final Path BLOCK_POLICY_CHECK_SCRIPT =
            Path.of("src/main/resources/lua/traffic/block_policy_check.lua");
    private static final Path DEDUCT_UNIFIED_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_unified.lua");
    private static final Path LUA_SCRIPT_TYPE_SOURCE =
            Path.of("src/main/java/com/pooli/traffic/domain/enums/TrafficLuaScriptType.java");

    @Test
    @DisplayName("1차 policy-check Lua는 whitelist 이후 immediate/repeat 순서로 평가한다")
    void keepsPolicyCheckOrder() throws IOException {
        String script = Files.readString(BLOCK_POLICY_CHECK_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if is_policy_enabled(policy_whitelist_key)",
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)"
        );
    }

    @Test
    @DisplayName("Lua 타입은 block policy와 통합 deduct만 차감 문자열 스크립트로 유지한다")
    void keepsUnifiedScriptTypesOnly() throws IOException {
        String enumSource = Files.readString(LUA_SCRIPT_TYPE_SOURCE, StandardCharsets.UTF_8);

        assertTrue(enumSource.contains("BLOCK_POLICY_CHECK(\"block_policy_check\", \"lua/traffic/block_policy_check.lua\")"));
        assertTrue(enumSource.contains("DEDUCT_UNIFIED(\"deduct_unified\", \"lua/traffic/deduct_unified.lua\")"));
        assertTrue(!enumSource.contains("DEDUCT_INDIVIDUAL"));
        assertTrue(!enumSource.contains("DEDUCT_SHARED"));
        assertTrue(!enumSource.contains("REFILL_GATE"));
    }

    @Test
    @DisplayName("통합 deduct Lua는 개인/공유/QoS 응답 계약을 반환한다")
    void unifiedDeductKeepsThreeWayResultContract() throws IOException {
        String script = Files.readString(DEDUCT_UNIFIED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(script.contains("indivDeducted"));
        assertTrue(script.contains("sharedDeducted"));
        assertTrue(script.contains("qosDeducted"));
        assertTrue(script.contains("\"GLOBAL_POLICY_HYDRATE\""));
        assertTrue(script.contains("\"HYDRATE\""));
        assertTrue(script.contains("\"NO_BALANCE\""));
    }

    @Test
    @DisplayName("통합 deduct Lua는 개인풀, 공유풀, QoS 순서로 차감한다")
    void unifiedDeductKeepsPoolOrder() throws IOException {
        String script = Files.readString(DEDUCT_UNIFIED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "local indiv_deducted = individual_unlimited and pool_target or math.min(individual_amount, pool_target)",
                "local shared_deducted = shared_unlimited and shared_target or math.min(shared_amount, shared_target)",
                "local qos_deducted = math.min(qos_limit, qos_target)"
        );
    }

    @Test
    @DisplayName("통합 deduct Lua는 amount 누락과 -1 무제한 sentinel을 분리한다")
    void unifiedDeductKeepsUnlimitedSentinelContract() throws IOException {
        String script = Files.readString(DEDUCT_UNIFIED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "local raw_individual_amount = redis.call(\"HGET\", individual_remaining_key, \"amount\")",
                "if raw_individual_amount == false or raw_individual_amount == nil then",
                "local individual_unlimited = individual_amount == -1",
                "local indiv_deducted = individual_unlimited and pool_target or math.min(individual_amount, pool_target)",
                "if indiv_deducted > 0 and not individual_unlimited then"
        );
    }

    private void assertAppearsInOrder(String script, String... fragments) {
        int previousIndex = -1;
        for (String fragment : fragments) {
            int currentIndex = script.indexOf(fragment, previousIndex + 1);
            assertTrue(currentIndex >= 0, "Required fragment not found. fragment=" + fragment);
            assertTrue(currentIndex > previousIndex, "Fragments must appear in order. fragment=" + fragment);
            previousIndex = currentIndex;
        }
    }
}
