package com.pooli.family.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "가족 공유 데이터 사용량 임계치 수정 요청 DTO")
public class UpdateSharedDataThresholdReqDto {
    @Schema(description = "새로운 임계치 값(단위: Byte)", example = "1500")
    private Integer newFamilyThreshold;
}
