package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of family policy apply/remove operation")
public record FamilyPolicyChangeResDto(
        @Schema(description = "Family identifier", example = "10")
        Long familyId,
        @Schema(description = "Policy identifier", example = "1002")
        Long policyId,
        @Schema(description = "Operation status", example = "APPLIED")
        String status,
        @Schema(description = "Processed timestamp", example = "2026-02-20T10:10:00")
        String processedAt
) {
    public static FamilyPolicyChangeResDto of(Long familyId, Long policyId, String status, String processedAt) {
        return new FamilyPolicyChangeResDto(familyId, policyId, status, processedAt);
    }
}

