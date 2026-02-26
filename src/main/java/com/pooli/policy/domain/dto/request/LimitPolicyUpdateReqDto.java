package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "제한 정책 단건 수정 요청")
public class LimitPolicyUpdateReqDto {
    @Schema(description = "제한 정책 식별자", example = "21")
    private Long limitPolicyId;
    @Schema(description = "수정할 정책 제한 데이터량 값", example = "1024")
    private Long policyValue;
}
