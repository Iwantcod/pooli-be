package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "정책 활성화 요청 바디")
public class PolicyActivationReqDto {
        @Schema(
                description = "활성화할 정책 식별자",
                example = "1003",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        private Long policyId;
}

