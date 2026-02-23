package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "공유풀 상세 페이지 데이터 조회 응답 DTO")
public class SharedPoolDetailResDto {

    @Schema(description = "기본 제공 데이터 총량(bite)", example = "10000")
    private Long basicDataAmount;

    @Schema(description = "기본 제공 데이터 잔여량(bite)", example = "4500")
    private Long remainingDataAmount;

    @Schema(description = "공유풀 데이터 총량(bite)", example = "20000")
    private Long sharedPoolTotalAmount;

    @Schema(description = "사용 가능한 공유풀 데이터 잔량(bite)", example = "12000")
    private Long sharedPoolRemainingAmount;
}