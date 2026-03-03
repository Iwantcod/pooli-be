package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "관리자 - 정책 생성 응답 dto")
public class AdminPolicyResDto {
	
	@Schema(description = "정책 ID", example = "1")
    private Integer policyId;
    
	@Schema(description = "정책 이름", example = "반복적 차단 정책")	
	private String policyName;

	@Schema(description = "정책 카테고리 ID", example = "1")	
	private Integer policyCategoryId;
	
	@Schema(description = "정책 활성화 여부", example = "false")	
	private Boolean isActive;

}
