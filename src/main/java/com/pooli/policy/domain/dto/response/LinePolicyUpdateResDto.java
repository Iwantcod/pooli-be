package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "회선 정책 일괄 수정 결과")
public class LinePolicyUpdateResDto {
    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "수정된 정책 항목 수", example = "5")
    private Integer updatedPolicyCount;

    @Schema(description = "수정 시각", example = "2026-02-19T12:00:00")
    private String updatedAt;
}
