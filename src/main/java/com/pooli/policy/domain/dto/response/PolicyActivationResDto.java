package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of policy activation")
public record PolicyActivationResDto(
        @Schema(description = "Policy identifier", example = "1003")
        Long policyId,
        @Schema(description = "Active flag after operation", example = "true")
        Boolean active,
        @Schema(description = "Activated timestamp", example = "2026-02-20T10:10:00")
        String activatedAt
) {
    public static PolicyActivationResDto of(Long policyId, Boolean active, String activatedAt) {
        return new PolicyActivationResDto(policyId, active, activatedAt);
    }
}

