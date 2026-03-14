package com.pooli.traffic.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 */
@Getter
@RequiredArgsConstructor
public enum TrafficLuaScriptType {
    DEDUCT_INDIVIDUAL("deduct_indiv", "lua/traffic/deduct_indiv.lua"),
    DEDUCT_SHARED("deduct_shared", "lua/traffic/deduct_shared.lua"),
    REFILL_GATE("refill_gate", "lua/traffic/refill_gate.lua"),
    LOCK_HEARTBEAT("lock_heartbeat", "lua/traffic/lock_heartbeat.lua"),
    LOCK_RELEASE("lock_release", "lua/traffic/lock_release.lua");

    private final String scriptName;
    private final String resourcePath;
}
