package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "관리자 - 정책 생성 요청 dto")
public class AdminPolicyReqDto {
	
	@Schema(description = "정책 이름", example = "반복적 차단 정책")	
	private String policyName;

	@Schema(description = "정책 카테고리 ID", example = "1")	
	private Integer policyCategoryId;
	
	@Schema(description = "활성화 여부", example = "true")
	private Boolean isActive;
}

