package com.pooli.traffic.domain.enums;

import lombok.Getter;

/**
 * 정책 Redis 동기화에서 사용하는 Lua 스크립트의 식별자와 classpath 위치를 관리합니다.
 */
@Getter
public enum TrafficPolicyLuaScriptType {
    /** 정책값 CAS 연산 스크립트 인스턴스 */
    POLICY_VALUE_CAS("policy_value_cas", "lua/traffic/policy_value_cas.lua"),

    /** 차단 스냅샷 CAS 스크립트 인스턴스 */
    REPEAT_BLOCK_SNAPSHOT_CAS("repeat_block_snapshot_cas", "lua/traffic/repeat_block_snapshot_cas.lua"),

    /** 단건 앱 정책 CAS 스크립트 인스턴스 */
    APP_POLICY_SINGLE_CAS("app_policy_single_cas", "lua/traffic/app_policy_single_cas.lua"),

    /** 앱 정책 스냅샷 일괄 CAS 스크립트 인스턴스 */
    APP_POLICY_SNAPSHOT_CAS("app_policy_snapshot_cas", "lua/traffic/app_policy_snapshot_cas.lua");

    /** 스크립트 파일 이름 */
    private final String scriptName;

    /** 리소스 파일 경로 */
    private final String resourcePath;

    /**
     * 각 스크립트의 파일 이름과 classpath 리소스 경로를 맵핑하는 생성자
     */
    TrafficPolicyLuaScriptType(String scriptName, String resourcePath) {
        this.scriptName = scriptName;
        this.resourcePath = resourcePath;
    }
}
