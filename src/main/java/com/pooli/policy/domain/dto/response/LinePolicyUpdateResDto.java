package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회선 정책 일괄 수정 결과")
public record LinePolicyUpdateResDto(
        @Schema(description = "회선 식별자", example = "101")
        Long lineId,
        @Schema(description = "수정된 정책 항목 수", example = "5")
        Integer updatedPolicyCount,
        @Schema(description = "수정 시각", example = "2026-02-19T12:00:00")
        String updatedAt
) {
    public static LinePolicyUpdateResDto of(Long lineId, Integer updatedPolicyCount, String updatedAt) {
        return new LinePolicyUpdateResDto(lineId, updatedPolicyCount, updatedAt);
    }
}

