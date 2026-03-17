package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "차단 정책 단건 수정 요청")
public class BlockPolicyUpdateReqDto {
    @Schema(description = "수정할 정책 활성화 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isActive;
}
