package com.pooli.traffic.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유풀 기여 Redis Lua 실행 결과입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficSharedPoolContributionLuaResult {
    /** 공유 풀 기여도 반영 Lua 스크립트의 실행 상태 결과 */
    private String status;
    /** 개인 한도 반영 적용량 (Byte 단위) */
    private Long individualApplied;
    /** 공유 한도 반영 적용량 (Byte 단위) */
    private Long sharedApplied;
}
