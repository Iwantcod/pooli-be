package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원의 제한 정책 항목")
public class LimitPolicyResDto {
    @Schema(description = "제한 정책 PK", example = "7201")
    private Long limitPolicyId;

    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "정책 값", example = "1024")
    private Integer policyValue;
}
