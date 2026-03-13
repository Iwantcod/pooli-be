package com.pooli.policy.domain.dto.response;

import java.time.LocalDateTime;

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

    @Schema(description = "정책 카테고리 이름", example = "차단")
    private String policyCategoryName;
	
	@Schema(description = "정책 활성화 여부", example = "false")	
	private Boolean isActive;

	@Schema(description = "신규 정책 여부", example = "true")	
	private Boolean isNew;
	
    @Schema(description = "최종 수정일", example = "2024-03-03T23:00:00")
    private LocalDateTime updatedAt;

}
