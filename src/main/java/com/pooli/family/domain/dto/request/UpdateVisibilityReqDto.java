package com.pooli.family.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "앱별 사용량 데이터 가족 공개 여부 설정 변경 요청 DTO")
public class UpdateVisibilityReqDto {

	@NotNull
    @Schema(description = "회선 식별자", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long lineId;

    @NotNull
    @Schema(description = "가족 공개 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isPublic;
}