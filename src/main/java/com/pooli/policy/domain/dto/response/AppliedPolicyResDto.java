package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Applied policy detail")
public record AppliedPolicyResDto(
        @Schema(description = "Policy identifier", example = "1001")
        Long policyId,
        @Schema(description = "Policy name", example = "Night Usage Block")
        String policyName,
        @Schema(description = "Policy type", example = "BLOCK")
        String policyType,
        @Schema(description = "Applied target type", example = "LINE")
        String appliedTarget,
        @Schema(description = "Target identifier (line or family)", example = "101")
        Long targetId,
        @Schema(description = "Applied timestamp", example = "2026-02-20T10:10:00")
        String appliedAt
) {
    public static AppliedPolicyResDto of(
            Long policyId,
            String policyName,
            String policyType,
            String appliedTarget,
            Long targetId,
            String appliedAt
    ) {
        return new AppliedPolicyResDto(policyId, policyName, policyType, appliedTarget, targetId, appliedAt);
    }
}

