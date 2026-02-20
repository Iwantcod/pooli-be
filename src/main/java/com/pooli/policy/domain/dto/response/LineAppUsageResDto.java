package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "App usage statistics for a specific line")
public record LineAppUsageResDto(
        @Schema(description = "Line identifier", example = "101")
        Long lineId,
        @Schema(description = "App identifier", example = "301")
        Long appId,
        @Schema(description = "App name", example = "YouTube")
        String appName,
        @Schema(description = "Used data in MB", example = "2450")
        Long usedMb,
        @Schema(description = "Usage share percent", example = "32")
        Integer usagePercent
) {
    public static LineAppUsageResDto of(
            Long lineId,
            Long appId,
            String appName,
            Long usedMb,
            Integer usagePercent
    ) {
        return new LineAppUsageResDto(lineId, appId, appName, usedMb, usagePercent);
    }
}

