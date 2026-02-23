package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족 공유 데이터 사용량 알람 임계치 조회 결과 DTO")
public class SharedDataThresholdResDto {
    @Schema(description = "가족 공유 데이터 임계치 설정 여부", example = "true")
    private Boolean isThresholdActive;
    @Schema(description = "가족 공유 데이터 사용량 임계치 값(단위: Byte)", example = "1000")
    private Integer familyThreshold;

}
