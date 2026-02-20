package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "가족 정책 적용/제거 결과")
public record FamilyPolicyChangeResDto(
        @Schema(description = "가족 식별자", example = "10")
        Long familyId,
        @Schema(description = "정책 식별자", example = "1002")
        Long policyId,
        @Schema(description = "처리 상태", example = "APPLIED")
        String status,
        @Schema(description = "처리 시각", example = "2026-02-20T10:10:00")
        String processedAt
) {
    public static FamilyPolicyChangeResDto of(Long familyId, Long policyId, String status, String processedAt) {
        return new FamilyPolicyChangeResDto(familyId, policyId, status, processedAt);
    }
}

