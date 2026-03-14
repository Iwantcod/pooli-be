package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lua 정책 스크립트의 우선순위/화이트리스트 예외 계약이 바뀌지 않도록 고정하는 테스트입니다.
 * 스크립트 실행 결과를 직접 검증하는 통합 테스트가 추가되기 전까지, 순서 회귀를 빠르게 감지합니다.
 */
class TrafficLuaPolicyContractTest {

    private static final Path INDIVIDUAL_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_indiv_tick.lua");
    private static final Path SHARED_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_shared_tick.lua");
    private static final Path REFILL_GATE_SCRIPT =
            Path.of("src/main/resources/lua/traffic/refill_gate.lua");

    @Test
    @DisplayName("개인풀 Lua는 whitelist -> immediate -> repeat -> daily -> app_daily -> app_speed 순서를 유지한다")
    void keepsIndividualPolicyPriorityOrder() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT);

        assertAppearsInOrder(
                script,
                "local whitelist_bypass = false",
                "if not whitelist_bypass then",
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if is_policy_enabled(policy_daily_key)",
                "if is_policy_enabled(policy_app_data_key)",
                "if is_policy_enabled(policy_app_speed_key)"
        );
    }

    @Test
    @DisplayName("공유풀 Lua는 whitelist -> immediate -> repeat -> daily -> monthly_shared -> app_daily -> app_speed 순서를 유지한다")
    void keepsSharedPolicyPriorityOrder() throws IOException {
        String script = Files.readString(SHARED_SCRIPT);

        assertAppearsInOrder(
                script,
                "local whitelist_bypass = false",
                "if not whitelist_bypass then",
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if is_policy_enabled(policy_daily_key)",
                "if is_policy_enabled(policy_shared_key)",
                "if is_policy_enabled(policy_app_data_key)",
                "if is_policy_enabled(policy_app_speed_key)"
        );
    }

    @Test
    @DisplayName("개인풀 Lua는 화이트리스트 우회 분기 안에서만 정책 차단을 평가한다")
    void evaluatesIndividualPolicyChecksInsideWhitelistGuard() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT);

        int bypassGuardIndex = script.indexOf("if not whitelist_bypass then");
        int immediateIndex = script.indexOf("if is_policy_enabled(policy_immediate_key)");
        int speedIndex = script.indexOf("if is_policy_enabled(policy_app_speed_key)");
        int endIndex = script.indexOf("\nend\n\n-- 실제 차감/사용량 반영");

        assertTrue(bypassGuardIndex >= 0, "whitelist bypass guard가 누락되면 안 됩니다.");
        assertTrue(immediateIndex > bypassGuardIndex, "즉시차단 검사는 whitelist guard 이후에 있어야 합니다.");
        assertTrue(speedIndex > immediateIndex, "속도 제한 검사는 정책 분기 블록의 마지막에 있어야 합니다.");
        assertTrue(endIndex > speedIndex, "정책 분기 블록 종료 후에만 실제 차감이 수행되어야 합니다.");
    }

    @Test
    @DisplayName("공유풀 Lua는 화이트리스트 우회 분기 안에서만 정책 차단을 평가한다")
    void evaluatesSharedPolicyChecksInsideWhitelistGuard() throws IOException {
        String script = Files.readString(SHARED_SCRIPT);

        int bypassGuardIndex = script.indexOf("if not whitelist_bypass then");
        int immediateIndex = script.indexOf("if is_policy_enabled(policy_immediate_key)");
        int monthlySharedIndex = script.indexOf("if is_policy_enabled(policy_shared_key)");
        int speedIndex = script.indexOf("if is_policy_enabled(policy_app_speed_key)");
        int endIndex = script.indexOf("\nend\n\n-- 실제 차감/사용량 반영");

        assertTrue(bypassGuardIndex >= 0, "whitelist bypass guard가 누락되면 안 됩니다.");
        assertTrue(immediateIndex > bypassGuardIndex, "즉시차단 검사는 whitelist guard 이후에 있어야 합니다.");
        assertTrue(monthlySharedIndex > immediateIndex, "월 공유풀 제한 검사는 일 제한 이후에 평가되어야 합니다.");
        assertTrue(speedIndex > monthlySharedIndex, "속도 제한 검사는 정책 분기 블록의 마지막에 있어야 합니다.");
        assertTrue(endIndex > speedIndex, "정책 분기 블록 종료 후에만 실제 차감이 수행되어야 합니다.");
    }

    @Test
    @DisplayName("개인풀 Lua는 차단 정책 검사 이후에 잔량 0 반환 분기를 평가한다")
    void checksIndividualBlockPolicyBeforeZeroBalanceBranch() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT);

        assertAppearsInOrder(
                script,
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if answer <= 0 then"
        );
    }

    @Test
    @DisplayName("공유풀 Lua는 차단 정책 검사 이후에 잔량 0 반환 분기를 평가한다")
    void checksSharedBlockPolicyBeforeZeroBalanceBranch() throws IOException {
        String script = Files.readString(SHARED_SCRIPT);

        assertAppearsInOrder(
                script,
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if answer <= 0 then"
        );
    }

    @Test
    @DisplayName("개인/공유 차감 Lua는 is_empty 필드를 직접 갱신하지 않는다")
    void doesNotWriteDbEmptyFlagInDeductScripts() throws IOException {
        String individualScript = Files.readString(INDIVIDUAL_SCRIPT);
        String sharedScript = Files.readString(SHARED_SCRIPT);

        assertTrue(!individualScript.contains("is_empty"), "개인풀 차감 Lua에서 is_empty 쓰기는 제거되어야 합니다.");
        assertTrue(!sharedScript.contains("is_empty"), "공유풀 차감 Lua에서 is_empty 쓰기는 제거되어야 합니다.");
    }

    @Test
    @DisplayName("refill gate는 threshold 비교보다 먼저 is_empty=1 스킵 분기를 평가한다")
    void refillGateChecksDbEmptyBeforeThreshold() throws IOException {
        String script = Files.readString(REFILL_GATE_SCRIPT);

        assertAppearsInOrder(
                script,
                "if is_empty == 1 then",
                "if current_amount > threshold then"
        );
    }

    private void assertAppearsInOrder(String script, String... fragments) {
        int previousIndex = -1;
        for (String fragment : fragments) {
            int currentIndex = script.indexOf(fragment);
            assertTrue(currentIndex >= 0, "스크립트에서 필수 조각을 찾지 못했습니다. fragment=" + fragment);
            assertTrue(
                    currentIndex > previousIndex,
                    "정책 순서가 바뀌었습니다. fragment=" + fragment + ", previousIndex=" + previousIndex + ", currentIndex=" + currentIndex
            );
            previousIndex = currentIndex;
        }
    }
}
