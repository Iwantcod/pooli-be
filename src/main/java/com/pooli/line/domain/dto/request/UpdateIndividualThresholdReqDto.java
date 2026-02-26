package com.pooli.line.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "개인 데이터 임계치 수정 요청 DTO")
public class UpdateIndividualThresholdReqDto {

    @Schema(description = "회선 ID", example = "1")
    private Integer lineId;

    @Schema(description = "개인 데이터 임계치(MB)", example = "3000")
    private Integer individualThreshold;

    @Schema(description = "임계치 활성화 여부", example = "true")
    private Boolean isThresholdActive;
}
