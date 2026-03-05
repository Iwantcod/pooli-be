package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "관리자 - 정책 활성화/비활성화 요청 dto")
public class AdminPolicyActiveReqDto {
        
	@Schema(description = "활성화할 정책 ID", example = "1003")
    private Integer policyId;
    
	@Schema(description = "활성화 여부", example = "true")
	private Boolean isActive;
}

