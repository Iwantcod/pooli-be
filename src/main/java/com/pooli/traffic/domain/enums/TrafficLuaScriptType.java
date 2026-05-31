package com.pooli.traffic.domain.enums;

import lombok.Getter;

/**
 * 트래픽 처리에서 사용하는 Lua 스크립트의 식별자와 classpath 위치를 관리합니다.
 * 스크립트 preload/execute 시 공통 키로 사용됩니다.
 */
@Getter
public enum TrafficLuaScriptType {
    /** 차단 정책 검증 스크립트 매핑 인스턴스 */
    BLOCK_POLICY_CHECK("block_policy_check", "lua/traffic/block_policy_check.lua"),

    /** 키 사전 존재 여부 검증 스크립트 매핑 인스턴스 */
    PREFLIGHT_KEY_EXISTENCE("preflight_key_existence", "lua/traffic/preflight_key_existence.lua"),

    /** 트래픽 차감 통합 스크립트 매핑 인스턴스 */
    DEDUCT_UNIFIED("deduct_unified", "lua/traffic/deduct_unified.lua"),

    /** 개인 잔액 스냅샷 동기화 스크립트 매핑 인스턴스 */
    HYDRATE_INDIVIDUAL_SNAPSHOT(
            "hydrate_individual_snapshot",
            "lua/traffic/hydrate_individual_snapshot.lua"
    ),

    /** 공유 잔액 스냅샷 동기화 스크립트 매핑 인스턴스 */
    HYDRATE_SHARED_SNAPSHOT("hydrate_shared_snapshot", "lua/traffic/hydrate_shared_snapshot.lua"),

    /** 분산 락 해제 스크립트 매핑 인스턴스 */
    LOCK_RELEASE("lock_release", "lua/traffic/lock_release.lua"),

    /** In-Flight 중복 방지 키 생성 스크립트 매핑 인스턴스 */
    IN_FLIGHT_CREATE_IF_ABSENT("in_flight_create_if_absent", "lua/traffic/in_flight_create_if_absent.lua"),

    /** In-Flight 키 재시도 카운트 증가 스크립트 매핑 인스턴스 */
    IN_FLIGHT_INCREMENT_RETRY_WITH_INIT(
            "in_flight_increment_retry_with_init",
            "lua/traffic/in_flight_increment_retry_with_init.lua"
    ),

    /** 공유 풀 기여도 반영 스크립트 매핑 인스턴스 */
    SHARED_POOL_CONTRIBUTION_APPLY(
            "shared_pool_contribution_apply",
            "lua/traffic/shared_pool_contribution_apply.lua"
    ),

    /** 공유 풀 기여도 복구 스크립트 매핑 인스턴스 */
    SHARED_POOL_CONTRIBUTION_RECOVER(
            "shared_pool_contribution_recover",
            "lua/traffic/shared_pool_contribution_recover.lua"
    ),

    /** 공유 풀 기여 데이터 정리 스크립트 매핑 인스턴스 */
    SHARED_POOL_CONTRIBUTION_CLEANUP(
            "shared_pool_contribution_cleanup",
            "lua/traffic/shared_pool_contribution_cleanup.lua"
    ),

    /** 데이터 복원 재실행 스크립트 매핑 인스턴스 */
    RESTORE_USAGE_REPLAY("restore_usage_replay", "lua/traffic/restore_usage_replay.lua"),

    /** 사용량 정정 스크립트 매핑 인스턴스 */
    RESTORE_USAGE_CORRECTION("restore_usage_correction", "lua/traffic/restore_usage_correction.lua");

    /** Lua 스크립트 파일명 식별 상수 */
    private final String scriptName;

    /** classpath 내 Lua 파일 상대 경로 */
    private final String resourcePath;

    /**
     * 각 스크립트의 고유 명칭과 리소스 경로를 지정하는 생성자
     */
    TrafficLuaScriptType(String scriptName, String resourcePath) {
        this.scriptName = scriptName;
        this.resourcePath = resourcePath;
    }
}
