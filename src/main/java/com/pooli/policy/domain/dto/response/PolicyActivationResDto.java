package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "정책 활성화 결과")
public record PolicyActivationResDto(
        @Schema(description = "정책 식별자", example = "1003")
        Long policyId,
        @Schema(description = "처리 후 활성화 상태", example = "true")
        Boolean active,
        @Schema(description = "활성화 시각", example = "2026-02-20T10:10:00")
        String activatedAt
) {
    public static PolicyActivationResDto of(Long policyId, Boolean active, String activatedAt) {
        return new PolicyActivationResDto(policyId, active, activatedAt);
    }
}

