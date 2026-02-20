package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "App-level policy detail for a specific line")
public record AppPolicyResDto(
        @Schema(description = "App identifier", example = "301")
        Long appId,
        @Schema(description = "App name", example = "YouTube")
        String appName,
        @Schema(description = "Policy type", example = "LIMIT")
        String policyType,
        @Schema(description = "Whether this app policy is enabled", example = "true")
        Boolean enabled,
        @Schema(description = "App daily limit in MB", example = "500")
        Integer dailyLimitMb
) {
    public static AppPolicyResDto of(
            Long appId,
            String appName,
            String policyType,
            Boolean enabled,
            Integer dailyLimitMb
    ) {
        return new AppPolicyResDto(appId, appName, policyType, enabled, dailyLimitMb);
    }
}

