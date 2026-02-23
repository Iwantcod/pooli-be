package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 회선의 앱별 사용량 통계")
public class LineAppUsageResDto {
    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "앱 식별자", example = "301")
    private Long appId;

    @Schema(description = "앱 이름", example = "YouTube")
    private String appName;

    @Schema(description = "사용 데이터(MB)", example = "2450")
    private Long usedMb;

    @Schema(description = "사용 비중(%)", example = "32")
    private Integer usagePercent;
}
