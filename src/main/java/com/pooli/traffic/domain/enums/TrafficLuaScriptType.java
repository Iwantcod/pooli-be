package com.pooli.traffic.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 트래픽 처리에서 사용하는 Lua 스크립트의 식별자와 classpath 위치를 관리합니다.
 * 스크립트 preload/execute 시 공통 키로 사용됩니다.
 */
@Getter
@RequiredArgsConstructor
public enum TrafficLuaScriptType {
    BLOCK_POLICY_CHECK("block_policy_check", "lua/traffic/block_policy_check.lua"),
    DEDUCT_UNIFIED("deduct_unified", "lua/traffic/deduct_unified.lua"),
    HYDRATE_INDIVIDUAL_SNAPSHOT(
            "hydrate_individual_snapshot",
            "lua/traffic/hydrate_individual_snapshot.lua"
    ),
    HYDRATE_SHARED_SNAPSHOT("hydrate_shared_snapshot", "lua/traffic/hydrate_shared_snapshot.lua"),
    LOCK_HEARTBEAT("lock_heartbeat", "lua/traffic/lock_heartbeat.lua"),
    LOCK_RELEASE("lock_release", "lua/traffic/lock_release.lua"),
    IN_FLIGHT_CREATE_IF_ABSENT("in_flight_create_if_absent", "lua/traffic/in_flight_create_if_absent.lua"),
    IN_FLIGHT_INCREMENT_RETRY_WITH_INIT(
            "in_flight_increment_retry_with_init",
            "lua/traffic/in_flight_increment_retry_with_init.lua"
    );

    private final String scriptName;
    private final String resourcePath;
}
