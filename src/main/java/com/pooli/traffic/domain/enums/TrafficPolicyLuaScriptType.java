package com.pooli.traffic.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 정책 Redis 동기화에서 사용하는 Lua 스크립트의 식별자와 classpath 위치를 관리합니다.
 */
@Getter
@RequiredArgsConstructor
public enum TrafficPolicyLuaScriptType {
    POLICY_VALUE_CAS("policy_value_cas", "lua/traffic/policy_value_cas.lua"),
    REPEAT_BLOCK_SNAPSHOT_CAS("repeat_block_snapshot_cas", "lua/traffic/repeat_block_snapshot_cas.lua"),
    APP_POLICY_SINGLE_CAS("app_policy_single_cas", "lua/traffic/app_policy_single_cas.lua"),
    APP_POLICY_SNAPSHOT_CAS("app_policy_snapshot_cas", "lua/traffic/app_policy_snapshot_cas.lua");

    private final String scriptName;
    private final String resourcePath;
}
