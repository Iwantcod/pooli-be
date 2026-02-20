package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 정책 목록 항목")
public record AdminPolicyResDto(
        @Schema(description = "정책 식별자", example = "1001")
        Long policyId,
        @Schema(description = "정책명", example = "야간 사용 차단")
        String policyName,
        @Schema(description = "정책 유형", example = "BLOCK")
        String policyType,
        @Schema(description = "정책 활성화 여부", example = "true")
        Boolean active,
        @Schema(description = "최종 수정 시각", example = "2026-02-20T10:10:00")
        String updatedAt
) {
    public static AdminPolicyResDto of(
            Long policyId,
            String policyName,
            String policyType,
            Boolean active,
            String updatedAt
    ) {
        return new AdminPolicyResDto(policyId, policyName, policyType, active, updatedAt);
    }
}

