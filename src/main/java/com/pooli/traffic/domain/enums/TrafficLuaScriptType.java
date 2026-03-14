package com.pooli.traffic.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ?몃옒??泥섎━?먯꽌 ?ъ슜?섎뒗 Lua ?ㅽ겕由쏀듃???앸퀎?먯? classpath ?꾩튂瑜?愿由ы빀?덈떎.
 * ?ㅽ겕由쏀듃 preload/execute ??怨듯넻 ?ㅻ줈 ?ъ슜?⑸땲??
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
