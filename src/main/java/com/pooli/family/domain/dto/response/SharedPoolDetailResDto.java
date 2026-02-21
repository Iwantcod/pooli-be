package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "공유풀 상세 페이지 데이터 조회 응답 DTO")
public class SharedPoolDetailResDto {

    @Schema(description = "기본 제공 데이터 총량(MB)", example = "10000")
    private Long basicDataAmount;

    @Schema(description = "기본 제공 데이터 잔여량(MB)", example = "4500")
    private Long remainingDataAmount;

    @Schema(description = "공유풀 데이터 총량(MB)", example = "20000")
    private Long sharedPoolTotalAmount;

    @Schema(description = "사용 가능한 공유풀 데이터 잔량(MB)", example = "12000")
    private Long sharedPoolRemainingAmount;
}