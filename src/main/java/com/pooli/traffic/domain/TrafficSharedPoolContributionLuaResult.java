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
    private String status;
    private Long individualApplied;
    private Long sharedApplied;
}
