package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "관리자 정책 목록 항목")
public class AdminPolicyResDto {
    @Schema(description = "정책 식별자", example = "1001")
    private Long policyId;

    @Schema(description = "정책명", example = "야간 사용 차단")
    private String policyName;

    @Schema(description = "정책 유형", example = "BLOCK")
    private String policyType;

    @Schema(description = "정책 활성화 여부", example = "true")
    private Boolean active;

    @Schema(description = "최종 수정 시각", example = "2026-02-20T10:10:00")
    private String updatedAt;
}
