package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "적용 정책 상세")
public record AppliedPolicyResDto(
        @Schema(description = "정책 식별자", example = "1001")
        Long policyId,
        @Schema(description = "정책명", example = "야간 사용 차단")
        String policyName,
        @Schema(description = "정책 유형", example = "BLOCK")
        String policyType,
        @Schema(description = "적용 대상 유형", example = "LINE")
        String appliedTarget,
        @Schema(description = "대상 식별자(회선/가족)", example = "101")
        Long targetId,
        @Schema(description = "적용 시각", example = "2026-02-20T10:10:00")
        String appliedAt
) {
    public static AppliedPolicyResDto of(
            Long policyId,
            String policyName,
            String policyType,
            String appliedTarget,
            Long targetId,
            String appliedAt
    ) {
        return new AppliedPolicyResDto(policyId, policyName, policyType, appliedTarget, targetId, appliedAt);
    }
}

