package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of line policy bulk update")
public record LinePolicyUpdateResDto(
        @Schema(description = "Line identifier", example = "101")
        Long lineId,
        @Schema(description = "Count of updated policy entries", example = "5")
        Integer updatedPolicyCount,
        @Schema(description = "Update timestamp", example = "2026-02-19T12:00:00")
        String updatedAt
) {
    public static LinePolicyUpdateResDto of(Long lineId, Integer updatedPolicyCount, String updatedAt) {
        return new LinePolicyUpdateResDto(lineId, updatedPolicyCount, updatedAt);
    }
}

