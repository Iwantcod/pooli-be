package com.pooli.line.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "회선,사용자 관련 요약 정보 응답 DTO")
public class LineUserSummaryResDto {
	
    @Schema(description = "회선 식별자", example = "1")
    private Long lineId;
    
    @Schema(description = "회선 식별자", example = "010-****-2222")
    private String phone;
    
    @Schema(description = "유저 식별자", example = "1")
    private Long userId;
    
    @Schema(description = "회선 식별자", example = "1")
    private String userName;

    @Schema(description = "회선 식별자", example = "1")
    private String email;

}
