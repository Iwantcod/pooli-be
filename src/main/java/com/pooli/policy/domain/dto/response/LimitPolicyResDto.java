package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 회선의 제한 정책 상세")
public class LimitPolicyResDto {
    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "일일 데이터 제한량(MB)", example = "1024")
    private Integer dailyLimitMb;

    @Schema(description = "월간 데이터 제한량(MB)", example = "20480")
    private Integer monthlyLimitMb;

    @Schema(description = "경고 임계치(%)", example = "80")
    private Integer warningThresholdPercent;
}
