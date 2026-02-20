package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 회선의 차단 정책 상세")
public class BlockPolicyResDto {
    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "로밍 데이터 차단 여부", example = "false")
    private Boolean blockRoaming;

    @Schema(description = "유료 콘텐츠 차단 여부", example = "true")
    private Boolean blockPaidContent;
}
