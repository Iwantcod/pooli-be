package com.pooli.traffic.service.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.traffic.domain.enums.TrafficPolicyLuaScriptType;

import lombok.RequiredArgsConstructor;

/**
 * 정책 키를 version CAS 규칙으로 Redis에 반영하는 서비스입니다.
 * 앱 정책 CAS 스크립트에는 정책 hash/set 키와 함께 QoS/앱 속도 제한 예약 키를 전달합니다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficPolicyVersionedRedisService {

    private final ObjectMapper objectMapper;
    private final TrafficPolicyLuaScriptInfraService trafficPolicyLuaScriptInfraService;

    /**
     * value/version 구조 Hash를 CAS 규칙으로 갱신합니다.
     */
    public PolicySyncResult syncVersionedValue(String key, String value, long version) {
        return trafficPolicyLuaScriptInfraService.executeLongScript(
                TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                List.of(key),
                String.valueOf(version),
                value
        );
    }

    /**
     * 반복 차단 Hash 전체 스냅샷을 CAS 규칙으로 재작성합니다.
     */
    public PolicySyncResult syncRepeatBlockSnapshot(String repeatBlockKey, Map<String, String> repeatHash, long version) {
        String payloadJson = toJson(repeatHash == null ? Map.of() : repeatHash);
        return trafficPolicyLuaScriptInfraService.executeLongScript(
                TrafficPolicyLuaScriptType.REPEAT_BLOCK_SNAPSHOT_CAS,
                List.of(repeatBlockKey),
                String.valueOf(version),
                payloadJson
        );
    }

    /**
     * 앱 정책 단건을 app_data/app_speed/whitelist 키에 CAS 규칙으로 반영합니다.
     * 비활성화 스크립트 경로에서는 함께 전달한 회선+앱 예약 키를 삭제합니다.
     */
    public PolicySyncResult syncAppPolicySingle(
            String appDataKey,
            String appSpeedKey,
            String appWhitelistKey,
            String qosSpeedLimitNextAvailableKey,
            int appId,
            boolean isActive,
            long dataLimit,
            int speedLimit,
            boolean isWhitelist,
            long version
    ) {
        return trafficPolicyLuaScriptInfraService.executeLongScript(
                TrafficPolicyLuaScriptType.APP_POLICY_SINGLE_CAS,
                List.of(appDataKey, appSpeedKey, appWhitelistKey, qosSpeedLimitNextAvailableKey),
                String.valueOf(version),
                String.valueOf(appId),
                isActive ? "1" : "0",
                String.valueOf(dataLimit),
                String.valueOf(speedLimit),
                isWhitelist ? "1" : "0"
        );
    }

    /**
     * 앱 정책 스냅샷을 CAS 규칙으로 전체 재작성합니다.
     * 추가 KEYS로 전달한 회선+앱 예약 키들은 스냅샷 재작성과 같은 Lua 원자 구간에서 삭제됩니다.
     */
    public PolicySyncResult syncAppPolicySnapshot(
            String appDataKey,
            String appSpeedKey,
            String appWhitelistKey,
            List<String> qosSpeedLimitNextAvailableKeys,
            Map<String, String> dataLimitHash,
            Map<String, String> speedLimitHash,
            Set<String> whitelistMembers,
            long version
    ) {
        List<String> keys = new ArrayList<>();
        keys.add(appDataKey);
        keys.add(appSpeedKey);
        keys.add(appWhitelistKey);
        if (qosSpeedLimitNextAvailableKeys != null) {
            keys.addAll(qosSpeedLimitNextAvailableKeys);
        }

        return trafficPolicyLuaScriptInfraService.executeLongScript(
                TrafficPolicyLuaScriptType.APP_POLICY_SNAPSHOT_CAS,
                keys,
                String.valueOf(version),
                toJson(dataLimitHash == null ? Map.of() : dataLimitHash),
                toJson(speedLimitHash == null ? Map.of() : speedLimitHash),
                toJson(whitelistMembers == null ? List.of() : whitelistMembers)
        );
    }

    /**
     * Lua payload 전달을 위한 JSON 직렬화를 수행합니다.
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Lua payload.", e);
        }
    }
}
