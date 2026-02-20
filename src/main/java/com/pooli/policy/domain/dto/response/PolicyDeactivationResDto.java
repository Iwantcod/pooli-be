package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "정책 비활성화 결과")
public record PolicyDeactivationResDto(
        @Schema(description = "정책 식별자", example = "1003")
        Long policyId,
        @Schema(description = "처리 후 활성화 상태", example = "false")
        Boolean active,
        @Schema(description = "비활성화 시각", example = "2026-02-20T10:10:00")
        String deactivatedAt
) {
    public static PolicyDeactivationResDto of(Long policyId, Boolean active, String deactivatedAt) {
        return new PolicyDeactivationResDto(policyId, active, deactivatedAt);
    }
}

