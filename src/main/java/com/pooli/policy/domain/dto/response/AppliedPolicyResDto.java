package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "적용 정책 상세")
public class AppliedPolicyResDto {
    @Schema(description = "정책 식별자", example = "1001")
    private Long policyId;

    @Schema(description = "정책명", example = "야간 사용 차단")
    private String policyName;

    @Schema(description = "정책 유형", example = "BLOCK")
    private String policyType;

    @Schema(description = "적용 대상 유형", example = "LINE")
    private String appliedTarget;

    @Schema(description = "대상 식별자(회선/가족)", example = "101")
    private Long targetId;

    @Schema(description = "적용 시각", example = "2026-02-20T10:10:00")
    private String appliedAt;
}
