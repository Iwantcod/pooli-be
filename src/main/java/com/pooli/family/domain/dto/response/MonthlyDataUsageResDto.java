package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "월별 데이터 사용량 조회 결과 DTO")
public class MonthlyDataUsageResDto {
    @Schema(description = "Month를 의미하는 정수", example = "1")
    private Integer month;
    @Schema(description = "사용량을 의미하는 정수", example = "10000")
    private Integer totalUsageData;
}
