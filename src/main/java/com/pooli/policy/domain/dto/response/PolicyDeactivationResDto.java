package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of policy deactivation")
public record PolicyDeactivationResDto(
        @Schema(description = "Policy identifier", example = "1003")
        Long policyId,
        @Schema(description = "Active flag after operation", example = "false")
        Boolean active,
        @Schema(description = "Deactivated timestamp", example = "2026-02-20T10:10:00")
        String deactivatedAt
) {
    public static PolicyDeactivationResDto of(Long policyId, Boolean active, String deactivatedAt) {
        return new PolicyDeactivationResDto(policyId, active, deactivatedAt);
    }
}

