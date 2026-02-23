package com.pooli.data.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "월별 데이터 사용량 조회 응답 DTO")
public class MonthlyDataUsageResDto {

    @Schema(description = "월별 사용량 목록")
    private List<MonthlyUsageDto> usages;

    @Schema(description = "평균 사용량(bite)", example = "1200")
    private Long averageAmount;



    public void updateAverageAmount(Long averageAmount) {
        this.averageAmount = averageAmount;
    }

    @Builder
    @Getter
    @Schema(description = "월별 사용량 DTO")
    public static class MonthlyUsageDto {

        @Schema(description = "연월", example = "2026-03")
        private String yearMonth;

        @Schema(description = "사용량(bite)", example = "1500")
        private Long usedAmount;

    }
}