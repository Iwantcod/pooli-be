package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Limit policy detail for a specific line")
public record LimitPolicyResDto(
        @Schema(description = "Line identifier", example = "101")
        Long lineId,
        @Schema(description = "Daily data limit in MB", example = "1024")
        Integer dailyLimitMb,
        @Schema(description = "Monthly data limit in MB", example = "20480")
        Integer monthlyLimitMb,
        @Schema(description = "Warning threshold percent", example = "80")
        Integer warningThresholdPercent
) {
    public static LimitPolicyResDto of(
            Long lineId,
            Integer dailyLimitMb,
            Integer monthlyLimitMb,
            Integer warningThresholdPercent
    ) {
        return new LimitPolicyResDto(lineId, dailyLimitMb, monthlyLimitMb, warningThresholdPercent);
    }
}

