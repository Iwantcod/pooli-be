package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족 공유풀 조회 응답 DTO")
public class FamilySharedPoolResDto {
    // 이거 MB로 줄까? KB가 기준일까?

    @Schema(description = "총 공유풀량(Byte)", example = "20000")
    private Long poolTotalData;

    @Schema(description = "공유풀 잔여량(Byte)", example = "8500")
    private Long poolRemainingData;

    @Schema(description = "공유풀 기본 제공량(Byte)", example = "8500")
    private Long poolBaseData;

    // 하위 항목은 Long인지 Integer도 괜찮은지 고민
    @Schema(description = "당월 공유풀 사용량(Byte)", example = "11500")
    private Long monthlyUsageAmount;

    @Schema(description = "당월 공유풀 제공량(Byte)", example = "20000")
    private Long monthlyContributionAmount;
}