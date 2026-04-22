package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lua 정책 스크립트의 역할 분리(1차 policy-check / 2차 deduct) 계약을 검증합니다.
 */
class TrafficLuaPolicyContractTest {

    private static final Path BLOCK_POLICY_CHECK_SCRIPT =
            Path.of("src/main/resources/lua/traffic/block_policy_check.lua");
    private static final Path DEDUCT_INDIVIDUAL_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_indiv.lua");
    private static final Path DEDUCT_SHARED_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_shared.lua");
    private static final Path REFILL_GATE_SCRIPT =
            Path.of("src/main/resources/lua/traffic/refill_gate.lua");

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
    @DisplayName("2차 deduct Lua는 immediate/repeat 차단 정책을 직접 검사하지 않는다")
    void deductScriptsDoNotCheckBlockPoliciesDirectly() throws IOException {
        String individualScript = Files.readString(DEDUCT_INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);
        String sharedScript = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(!individualScript.contains("BLOCKED_IMMEDIATE"), "Individual deduct must not return BLOCKED_IMMEDIATE.");
        assertTrue(!individualScript.contains("BLOCKED_REPEAT"), "Individual deduct must not return BLOCKED_REPEAT.");
        assertTrue(!sharedScript.contains("BLOCKED_IMMEDIATE"), "Shared deduct must not return BLOCKED_IMMEDIATE.");
        assertTrue(!sharedScript.contains("BLOCKED_REPEAT"), "Shared deduct must not return BLOCKED_REPEAT.");
    }

    @Test
    @DisplayName("2차 deduct Lua는 whitelist bypass flag 입력을 사용한다")
    void deductScriptsUseWhitelistBypassFlag() throws IOException {
        String individualScript = Files.readString(DEDUCT_INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);
        String sharedScript = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(
                individualScript.contains("local whitelist_bypass_flag = tonumber(ARGV[7] or \"0\")"),
                "Individual deduct script must consume whitelist bypass flag from ARGV[7]."
        );
        assertTrue(
                sharedScript.contains("local whitelist_bypass_flag = tonumber(ARGV[8] or \"0\")"),
                "Shared deduct script must consume whitelist bypass flag from ARGV[8]."
        );
    }

    @Test
    @DisplayName("2차 deduct Lua는 global policy guard 이후 잔량 조회를 수행한다")
    void checksGlobalPolicyGuardBeforeBalanceRead() throws IOException {
        String individualScript = Files.readString(DEDUCT_INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);
        String sharedScript = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                individualScript,
                "if has_missing_global_policy_key(",
                "return as_json(0, \"GLOBAL_POLICY_HYDRATE\")",
                "local current_amount = tonumber(redis.call(\"HGET\", remaining_key, \"amount\") or \"-1\")"
        );
        assertAppearsInOrder(
                sharedScript,
                "if has_missing_global_policy_key(",
                "return as_json(0, \"GLOBAL_POLICY_HYDRATE\")",
                "local current_amount = tonumber(redis.call(\"HGET\", remaining_key, \"amount\") or \"-1\")"
        );
    }

    @Test
    @DisplayName("공유풀 Lua는 QoS 보정 키를 추가로 받고 QOS 상태를 반환할 수 있다")
    void sharedScriptAcceptsQosKeyAndStatus() throws IOException {
        String script = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(
                script.contains("local individual_remaining_key = KEYS[20]"),
                "Shared script must accept individual remaining key for qos lookup."
        );
        assertTrue(
                script.contains("\"QOS\""),
                "Shared script must include QOS status."
        );
        assertTrue(
                script.contains("local allow_qos_fallback = tonumber(ARGV[13] or \"0\")"),
                "Shared script must accept allow_qos_fallback flag."
        );
    }

    @Test
    @DisplayName("공유 DB 리필 전에는 allow 플래그 없이는 QOS로 우회하지 않는다")
    void sharedScriptRequiresAllowQosFlagBeforeFallback() throws IOException {
        String script = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if answer <= 0 then",
                "if allow_qos_fallback ~= 1 then",
                "return as_json(0, final_status)",
                "local qos_answer, qos_status = resolve_qos_fallback("
        );
    }

    @Test
    @DisplayName("allow 플래그로 QoS 보정이 적용되면 공유풀 잔량/월 사용량 차감은 건너뛴다")
    void sharedScriptSkipsSharedDeductWhenQosFallbackApplies() throws IOException {
        String script = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if answer <= 0 then",
                "if allow_qos_fallback ~= 1 then",
                "local qos_answer, qos_status = resolve_qos_fallback(",
                "used_qos_fallback = true",
                "if not used_qos_fallback then",
                "redis.call(\"HINCRBY\", remaining_key, \"amount\", -answer)"
        );
    }

    @Test
    @DisplayName("공유 QoS fallback의 speed 정책은 whitelist 우회가 아닐 때만 적용된다")
    void sharedScriptAppliesSpeedPolicyForQosFallbackOnlyWhenWhitelistBypassDisabled() throws IOException {
        String script = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(
                script.contains("if (not whitelist_bypass) and is_policy_enabled(policy_app_speed_key) then"),
                "Shared QoS fallback must skip app speed policy when whitelist bypass is enabled."
        );
        assertAppearsInOrder(
                script,
                "local qos_answer, qos_status = resolve_qos_fallback(",
                "qos_capped_target,",
                "qos_policy_status,",
                "whitelist_bypass,",
                "policy_app_speed_key,"
        );
        assertAppearsInOrder(
                script,
                "local speed_used = tonumber(redis.call(\"GET\", speed_bucket_key) or \"0\")",
                "local speed_remaining = math.max(0, raw_app_speed_limit - speed_used)",
                "if speed_remaining <= 0 then",
                "fallback_answer = math.min(fallback_answer, speed_remaining)"
        );
        assertTrue(
                script.contains("return 0, \"HIT_APP_SPEED\""),
                "Shared script must return HIT_APP_SPEED when speed remaining budget is exhausted."
        );
    }

    @Test
    @DisplayName("공유 QoS fallback 입력은 monthly 제외 cap(qos_capped_target)을 사용한다")
    void sharedScriptUsesQosCappedTargetWithoutMonthlyLimit() throws IOException {
        String script = Files.readString(DEDUCT_SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(
                script.contains("local qos_capped_target = target_data"),
                "Shared script must keep a dedicated qos cap target."
        );
        assertTrue(
                script.contains("allow_qos_fallback ~= 1 and is_policy_enabled(policy_shared_key)"),
                "Shared monthly policy must be skipped in QoS fallback call."
        );
        assertTrue(
                script.contains("local qos_answer, qos_status = resolve_qos_fallback("),
                "Shared script must call qos fallback resolver."
        );
        assertTrue(
                script.contains("qos_capped_target,"),
                "Shared script must pass qos_capped_target to qos fallback."
        );
    }

    @Test
    @DisplayName("refill gate는 is_empty/threshold를 원인별 SKIP 상태로 구분한다")
    void refillGateChecksDbEmptyBeforeThreshold() throws IOException {
        String script = Files.readString(REFILL_GATE_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if is_empty == 1 then",
                "return \"SKIP_DB_EMPTY\"",
                "if current_amount >= threshold then"
        );
        assertTrue(
                script.contains("return \"SKIP_THRESHOLD\""),
                "Refill gate must return SKIP_THRESHOLD when current amount is above threshold."
        );
    }

    private void assertAppearsInOrder(String script, String... fragments) {
        int previousIndex = -1;
        for (String fragment : fragments) {
            int searchFromIndex = previousIndex + 1;
            int currentIndex = script.indexOf(fragment, searchFromIndex);
            assertTrue(currentIndex >= 0, "Required fragment not found. fragment=" + fragment);
            assertTrue(
                    currentIndex > previousIndex,
                    "Fragments must appear in order. fragment=" + fragment + ", previousIndex=" + previousIndex + ", currentIndex=" + currentIndex
            );
            previousIndex = currentIndex;
        }
    }
}
