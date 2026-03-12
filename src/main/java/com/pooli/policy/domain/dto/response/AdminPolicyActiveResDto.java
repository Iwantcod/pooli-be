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
@Schema(description = "관리자 - 정책 활성화 응답 Dto")
public class AdminPolicyActiveResDto {
	
    @Schema(description = "정책 식별자", example = "1003")
    private Integer policyId;

    @Schema(description = "활성화 상태", example = "true")
    private Boolean isActive;

    @Schema(description = "최종 수정일", example = "2024-03-03T23:00:00")
    private LocalDateTime updatedAt;

}
