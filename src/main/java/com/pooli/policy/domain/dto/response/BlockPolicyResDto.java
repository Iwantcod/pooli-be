package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원의 차단 정책 항목")
public class BlockPolicyResDto {
    @Schema(description = "차단 정책 PK", example = "7101")
    private Long blockPolicyId;

    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "정책 활성화 여부", example = "true")
    private Boolean enabled;
}
