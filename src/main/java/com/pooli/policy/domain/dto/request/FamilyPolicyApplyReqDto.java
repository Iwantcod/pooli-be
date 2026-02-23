package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "가족 정책 적용 요청 바디")
public class FamilyPolicyApplyReqDto {
        @Schema(
                description = "적용할 정책 식별자",
                example = "1002",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        private Long policyId;
}

