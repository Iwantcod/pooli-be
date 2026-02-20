package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Request body for bulk line policy update")
public record LinePolicyUpdateReqDto(
        @Schema(
                description = "Whether line-level block policy is enabled",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Boolean blockPolicyEnabled,
        @Schema(
                description = "Daily data limit in MB",
                example = "1024",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Integer dailyLimitMb,
        @Schema(
                description = "Allowed app policy identifiers",
                example = "[11, 12, 13]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<Long> allowedAppPolicyIds
) {
}

