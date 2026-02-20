package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "특정 회선의 앱 사용량 통계")
public record LineAppUsageResDto(
        @Schema(description = "회선 식별자", example = "101")
        Long lineId,
        @Schema(description = "앱 식별자", example = "301")
        Long appId,
        @Schema(description = "앱 이름", example = "YouTube")
        String appName,
        @Schema(description = "사용 데이터(MB)", example = "2450")
        Long usedMb,
        @Schema(description = "사용 비중(%)", example = "32")
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

