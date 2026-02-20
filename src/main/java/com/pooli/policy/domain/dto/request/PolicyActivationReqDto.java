package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for activating a policy")
public record PolicyActivationReqDto(
        @Schema(
                description = "Policy identifier to activate",
                example = "1003",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long policyId
) {
}

