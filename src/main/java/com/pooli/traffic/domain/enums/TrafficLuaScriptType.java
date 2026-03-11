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
    DEDUCT_INDIV_TICK("deduct_indiv_tick", "lua/traffic/deduct_indiv_tick.lua"),
    DEDUCT_SHARED_TICK("deduct_shared_tick", "lua/traffic/deduct_shared_tick.lua"),
    REFILL_GATE("refill_gate", "lua/traffic/refill_gate.lua"),
    LOCK_HEARTBEAT("lock_heartbeat", "lua/traffic/lock_heartbeat.lua"),
    LOCK_RELEASE("lock_release", "lua/traffic/lock_release.lua");

    private final String scriptName;
    private final String resourcePath;
}
