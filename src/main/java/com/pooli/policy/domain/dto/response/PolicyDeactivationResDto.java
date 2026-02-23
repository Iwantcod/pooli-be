package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "정책 비활성화 결과")
public class PolicyDeactivationResDto {
    @Schema(description = "정책 식별자", example = "1003")
    private Long policyId;

    @Schema(description = "활성화 상태", example = "false")
    private Boolean active;

    @Schema(description = "비활성화 시각", example = "2026-02-20T10:10:00")
    private String deactivatedAt;
}
