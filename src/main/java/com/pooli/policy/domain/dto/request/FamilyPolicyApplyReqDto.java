package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for applying a policy to a family")
public record FamilyPolicyApplyReqDto(
        @Schema(
                description = "Policy identifier to apply",
                example = "1002",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long policyId
) {
}

