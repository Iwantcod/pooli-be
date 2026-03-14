package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lua ?類ㅼ퐠 ??쎄쾿?깆?????怨쀪퐨??뽰맄/?遺우뵠?紐꺿봺??쎈뱜 ??됱뇚 ?④쑴鍮??獄쏅뗀??? ??낅즲嚥??⑥쥙???롫뮉 ???뮞?紐꾩뿯??덈뼄.
 * ??쎄쾿?깆?????쎈뻬 野껉퀗?든몴?筌욊낯??野꺜筌앹빜釉?????? ???뮞?硫? ?곕떽???띾┛ ?袁㏉돱筌왖, ??뽮퐣 ???????쥓?ㅵ칰?揶쏅Ŋ???몃빍??
 */
class TrafficLuaPolicyContractTest {

    private static final Path INDIVIDUAL_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_indiv.lua");
    private static final Path SHARED_SCRIPT =
            Path.of("src/main/resources/lua/traffic/deduct_shared.lua");
    private static final Path REFILL_GATE_SCRIPT =
            Path.of("src/main/resources/lua/traffic/refill_gate.lua");

    @Test
    void keepsIndividualPolicyPriorityOrder() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);

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
    void keepsSharedPolicyPriorityOrder() throws IOException {
        String script = Files.readString(SHARED_SCRIPT, StandardCharsets.UTF_8);

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
    void evaluatesIndividualPolicyChecksInsideWhitelistGuard() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);

        int bypassGuardIndex = script.indexOf("if not whitelist_bypass then");
        int immediateIndex = script.indexOf("if is_policy_enabled(policy_immediate_key)");
        int speedIndex = script.indexOf("if is_policy_enabled(policy_app_speed_key)");
        int writeIndex = script.indexOf("redis.call(\"HINCRBY\", remaining_key, \"amount\", -answer)");

        assertTrue(bypassGuardIndex >= 0, "whitelist bypass guard揶쎛 ?袁⑥뵭??롢늺 ????몃빍??");
        assertTrue(immediateIndex > bypassGuardIndex, "筌앸맩?놅㎕?ㅻ뼊 野꺜????whitelist guard ??꾩뜎????됰선????몃빍??");
        assertTrue(speedIndex > immediateIndex, "??얜즲 ??쀫립 野꺜?????類ㅼ퐠 ?브쑨由??됰뗀以??筌띾뜆?筌띾맩肉???됰선????몃빍??");
        assertTrue(writeIndex > speedIndex, "?類ㅼ퐠 ?브쑨由??됰뗀以??ル굝利??袁⑸퓠筌???쇱젫 筌△몿而????묐뻬??뤿선????몃빍??");
    }

    @Test
    void evaluatesSharedPolicyChecksInsideWhitelistGuard() throws IOException {
        String script = Files.readString(SHARED_SCRIPT, StandardCharsets.UTF_8);

        int bypassGuardIndex = script.indexOf("if not whitelist_bypass then");
        int immediateIndex = script.indexOf("if is_policy_enabled(policy_immediate_key)");
        int monthlySharedIndex = script.indexOf("if is_policy_enabled(policy_shared_key)");
        int speedIndex = script.indexOf("if is_policy_enabled(policy_app_speed_key)");
        int writeIndex = script.indexOf("redis.call(\"HINCRBY\", remaining_key, \"amount\", -answer)");

        assertTrue(bypassGuardIndex >= 0, "whitelist bypass guard揶쎛 ?袁⑥뵭??롢늺 ????몃빍??");
        assertTrue(immediateIndex > bypassGuardIndex, "筌앸맩?놅㎕?ㅻ뼊 野꺜????whitelist guard ??꾩뜎????됰선????몃빍??");
        assertTrue(monthlySharedIndex > immediateIndex, "???⑤벊??? ??쀫립 野꺜????????쀫립 ??꾩뜎???????뤿선????몃빍??");
        assertTrue(speedIndex > monthlySharedIndex, "??얜즲 ??쀫립 野꺜?????類ㅼ퐠 ?브쑨由??됰뗀以??筌띾뜆?筌띾맩肉???됰선????몃빍??");
        assertTrue(writeIndex > speedIndex, "?類ㅼ퐠 ?브쑨由??됰뗀以??ル굝利??袁⑸퓠筌???쇱젫 筌△몿而????묐뻬??뤿선????몃빍??");
    }

    @Test
    void checksIndividualBlockPolicyBeforeZeroBalanceBranch() throws IOException {
        String script = Files.readString(INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if answer <= 0 then"
        );
    }

    @Test
    void checksSharedBlockPolicyBeforeZeroBalanceBranch() throws IOException {
        String script = Files.readString(SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if is_policy_enabled(policy_immediate_key)",
                "if is_policy_enabled(policy_repeat_key)",
                "if answer <= 0 then"
        );
    }

    @Test
    void doesNotWriteDbEmptyFlagInDeductScripts() throws IOException {
        String individualScript = Files.readString(INDIVIDUAL_SCRIPT, StandardCharsets.UTF_8);
        String sharedScript = Files.readString(SHARED_SCRIPT, StandardCharsets.UTF_8);

        assertTrue(!individualScript.contains("is_empty"), "揶쏆뮇??? 筌△몿而?Lua?癒?퐣 is_empty ?怨뚮┛????볤탢??뤿선????몃빍??");
        assertTrue(!sharedScript.contains("is_empty"), "?⑤벊??? 筌△몿而?Lua?癒?퐣 is_empty ?怨뚮┛????볤탢??뤿선????몃빍??");
    }

    @Test
    void refillGateChecksDbEmptyBeforeThreshold() throws IOException {
        String script = Files.readString(REFILL_GATE_SCRIPT, StandardCharsets.UTF_8);

        assertAppearsInOrder(
                script,
                "if is_empty == 1 then",
                "if current_amount >= threshold then"
        );
    }

    private void assertAppearsInOrder(String script, String... fragments) {
        int previousIndex = -1;
        for (String fragment : fragments) {
            int currentIndex = script.indexOf(fragment);
            assertTrue(currentIndex >= 0, "??쎄쾿?깆???癒?퐣 ?袁⑸땾 鈺곌퀗而??筌≪뼚? 筌륁궢六??щ빍?? fragment=" + fragment);
            assertTrue(
                    currentIndex > previousIndex,
                    "?類ㅼ퐠 ??뽮퐣揶쎛 獄쏅뗀??????щ빍?? fragment=" + fragment + ", previousIndex=" + previousIndex + ", currentIndex=" + currentIndex
            );
            previousIndex = currentIndex;
        }
    }
}
