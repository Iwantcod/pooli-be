package com.pooli.traffic.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.traffic.domain.enums.TrafficPolicyLuaScriptType;

@ExtendWith(MockitoExtension.class)
class TrafficPolicyVersionedRedisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TrafficPolicyLuaScriptInfraService trafficPolicyLuaScriptInfraService;

    private TrafficPolicyVersionedRedisService trafficPolicyVersionedRedisService;

    @BeforeEach
    void setUp() {
        trafficPolicyVersionedRedisService = new TrafficPolicyVersionedRedisService(
                objectMapper,
                trafficPolicyLuaScriptInfraService
        );
    }

    @Nested
    @DisplayName("Lua infra 위임 계약")
    class LuaInfraDelegationTest {

        @Test
        @DisplayName("value/version Hash CAS는 policy value script로 위임")
        void delegatesVersionedValueCas() {
            when(trafficPolicyLuaScriptInfraService.executeLongScript(
                    eq(TrafficPolicyLuaScriptType.POLICY_VALUE_CAS),
                    eq(List.of("policy:key")),
                    any(Object[].class)
            )).thenReturn(PolicySyncResult.SUCCESS);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue(
                    "policy:key",
                    "1",
                    10L
            );

            assertEquals(PolicySyncResult.SUCCESS, result);
            verify(trafficPolicyLuaScriptInfraService).executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );
        }

        @Test
        @DisplayName("repeat block snapshot CAS는 snapshot script로 위임")
        void delegatesRepeatBlockSnapshotCas() {
            when(trafficPolicyLuaScriptInfraService.executeLongScript(
                    eq(TrafficPolicyLuaScriptType.REPEAT_BLOCK_SNAPSHOT_CAS),
                    eq(List.of("repeat:block:key")),
                    any(Object[].class)
            )).thenReturn(PolicySyncResult.STALE_REJECTED);

            Map<String, String> repeatHash = new LinkedHashMap<>();
            repeatHash.put("MON:start", "0");
            repeatHash.put("MON:end", "3600");

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncRepeatBlockSnapshot(
                    "repeat:block:key",
                    repeatHash,
                    11L
            );

            assertEquals(PolicySyncResult.STALE_REJECTED, result);
            verify(trafficPolicyLuaScriptInfraService).executeLongScript(
                    TrafficPolicyLuaScriptType.REPEAT_BLOCK_SNAPSHOT_CAS,
                    List.of("repeat:block:key"),
                    "11",
                    "{\"MON:start\":\"0\",\"MON:end\":\"3600\"}"
            );
        }

        @Test
        @DisplayName("app policy 단건 CAS는 single script로 위임")
        void delegatesAppPolicySingleCas() {
            when(trafficPolicyLuaScriptInfraService.executeLongScript(
                    eq(TrafficPolicyLuaScriptType.APP_POLICY_SINGLE_CAS),
                    eq(List.of("app:data:key", "app:speed:key", "app:white:key")),
                    any(Object[].class)
            )).thenReturn(PolicySyncResult.SUCCESS);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncAppPolicySingle(
                    "app:data:key",
                    "app:speed:key",
                    "app:white:key",
                    301,
                    true,
                    1_000L,
                    2_500,
                    false,
                    12L
            );

            assertEquals(PolicySyncResult.SUCCESS, result);
            verify(trafficPolicyLuaScriptInfraService).executeLongScript(
                    TrafficPolicyLuaScriptType.APP_POLICY_SINGLE_CAS,
                    List.of("app:data:key", "app:speed:key", "app:white:key"),
                    "12",
                    "301",
                    "1",
                    "1000",
                    "2500",
                    "0"
            );
        }

        @Test
        @DisplayName("app policy snapshot CAS는 snapshot script로 위임")
        void delegatesAppPolicySnapshotCas() {
            when(trafficPolicyLuaScriptInfraService.executeLongScript(
                    eq(TrafficPolicyLuaScriptType.APP_POLICY_SNAPSHOT_CAS),
                    eq(List.of("app:data:key", "app:speed:key", "app:white:key")),
                    any(Object[].class)
            )).thenReturn(PolicySyncResult.RETRYABLE_FAILURE);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncAppPolicySnapshot(
                    "app:data:key",
                    "app:speed:key",
                    "app:white:key",
                    Map.of("301", "1000"),
                    Map.of("301", "2500"),
                    Set.of("301"),
                    13L
            );

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
            verify(trafficPolicyLuaScriptInfraService).executeLongScript(
                    TrafficPolicyLuaScriptType.APP_POLICY_SNAPSHOT_CAS,
                    List.of("app:data:key", "app:speed:key", "app:white:key"),
                    "13",
                    "{\"301\":\"1000\"}",
                    "{\"301\":\"2500\"}",
                    "[\"301\"]"
            );
        }
    }
}
