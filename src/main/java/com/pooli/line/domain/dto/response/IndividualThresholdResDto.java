package com.pooli.line.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "개인 데이터 임계치 조회 응답 DTO")
public class IndividualThresholdResDto {
    @Schema(description = "개인 데이터 임계치(byte)", example = "3000")
    private Long individualThreshold;

    @Schema(description = "임계치 활성화 여부", example = "true")
    private Boolean isThresholdActive;
}
