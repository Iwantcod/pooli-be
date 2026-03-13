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
@Schema(description = "관리자 - 정책 카테고리 응답 Dto")
public class AdminPolicyCateResDto {
    
	@Schema(description = "카테고리 ID", example = "1001")
    private Integer policyCategoryId;

    @Schema(description = "카테고리 이름", example = "차단")
    private String policyCategoryName;

    @Schema(description = "최종 수정일", example = "2024-03-03T23:00:00")
    private LocalDateTime updatedAt;
}
