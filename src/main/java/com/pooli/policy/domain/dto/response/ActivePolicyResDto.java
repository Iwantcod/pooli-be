package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Active policy that can be applied to a family")
public record ActivePolicyResDto(
        @Schema(description = "Policy identifier", example = "1001")
        Long policyId,
        @Schema(description = "Policy name", example = "Night Usage Block")
        String policyName,
        @Schema(description = "Policy type", example = "BLOCK")
        String policyType,
        @Schema(description = "Policy description", example = "Blocks data usage from 22:00 to 06:00.")
        String description
) {
    public static ActivePolicyResDto of(Long policyId, String policyName, String policyType, String description) {
        return new ActivePolicyResDto(policyId, policyName, policyType, description);
    }
}

