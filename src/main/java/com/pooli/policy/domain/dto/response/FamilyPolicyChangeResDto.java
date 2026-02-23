package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족 정책 적용/제거 결과")
public class FamilyPolicyChangeResDto {
    @Schema(description = "가족 식별자", example = "10")
    private Long familyId;

    @Schema(description = "정책 식별자", example = "1002")
    private Long policyId;

    @Schema(description = "처리 상태", example = "APPLIED")
    private String status;

    @Schema(description = "처리 시각", example = "2026-02-20T10:10:00")
    private String processedAt;
}
