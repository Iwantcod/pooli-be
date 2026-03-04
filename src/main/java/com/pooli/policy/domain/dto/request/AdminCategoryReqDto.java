package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "관리자 - 정책 카테고리 생성")
public class AdminCategoryReqDto {
	
    @Schema(description = "카테고리 ID", example = "1001")
    private Integer policyCategoryId;
    
    @Schema(description = "카테고리 이름", example = "차단")
    private String policyCategoryName;
}
