package com.pooli.data.domain.dto.response;

import java.util.List;

import com.pooli.data.domain.dto.response.AppDataUsageResDto.AppUsageDto;

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
@Schema(description = "데이터 사용량 조회 응답 DTO")
public class DataUsageResDto {
	
	
    @Schema(description = "당월 조회 여부", example = "true")
    private Boolean isCurrentMonth;

    @Schema(description = "개인 데이터 사용량", example = "5200")
    private Long personalUsedAmount;

    @Schema(description = "공유풀 데이터 사용량", example = "5200")
    private Long sharedPoolUsedAmount;

    @Schema(description = "개인 데이터 총량", example = "5200")
    private Long personalTotalAmount;

    @Schema(description = "공유풀 데이터 총량", example = "5200")
    private Long sharedPoolTotalAmount;

    @Schema(description = "공유 데이터 잔여량(메인 카드 기준)", example = "4200")
    private Long sharedPoolRemainingAmount;


}
