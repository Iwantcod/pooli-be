package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Policy item for admin policy list")
public record AdminPolicyResDto(
        @Schema(description = "Policy identifier", example = "1001")
        Long policyId,
        @Schema(description = "Policy name", example = "Night Usage Block")
        String policyName,
        @Schema(description = "Policy type", example = "BLOCK")
        String policyType,
        @Schema(description = "Whether this policy is active", example = "true")
        Boolean active,
        @Schema(description = "Last updated timestamp", example = "2026-02-20T10:10:00")
        String updatedAt
) {
    public static AdminPolicyResDto of(
            Long policyId,
            String policyName,
            String policyType,
            Boolean active,
            String updatedAt
    ) {
        return new AdminPolicyResDto(policyId, policyName, policyType, active, updatedAt);
    }
}

