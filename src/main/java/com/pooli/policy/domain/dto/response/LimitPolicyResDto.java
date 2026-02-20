package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "특정 회선의 제한 정책 상세")
public record LimitPolicyResDto(
        @Schema(description = "회선 식별자", example = "101")
        Long lineId,
        @Schema(description = "일일 데이터 제한(MB)", example = "1024")
        Integer dailyLimitMb,
        @Schema(description = "월간 데이터 제한(MB)", example = "20480")
        Integer monthlyLimitMb,
        @Schema(description = "경고 임계치(%)", example = "80")
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

