package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "특정 회선의 차단 정책 상세")
public record BlockPolicyResDto(
        @Schema(description = "회선 식별자", example = "101")
        Long lineId,
        @Schema(description = "성인 콘텐츠 차단 여부", example = "true")
        Boolean blockAdultContent,
        @Schema(description = "로밍 데이터 차단 여부", example = "false")
        Boolean blockRoaming,
        @Schema(description = "유료 콘텐츠 차단 여부", example = "true")
        Boolean blockPaidContent
) {
    public static BlockPolicyResDto of(
            Long lineId,
            Boolean blockAdultContent,
            Boolean blockRoaming,
            Boolean blockPaidContent
    ) {
        return new BlockPolicyResDto(lineId, blockAdultContent, blockRoaming, blockPaidContent);
    }
}

