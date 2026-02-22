package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@Schema(description = "특정 구성원의 특정 월 데이터 사용 세부 정보 조회 결과 DTO")
public class MonthlyDataUsageDetailResDto {
    @Schema(description = "가족 공개 여부 설정 값", example = "true")
    private Boolean isPublic;
    @Schema(description = "앱 별 데이터 사용량 정보 리스트")
    private List<DataUsageDetail> dataUsageDetails;
    @Getter
    @Builder
    @Schema(description = "특정 월의 앱 별 데이터 사용량 정보 DTO")
    public static class DataUsageDetail {
        @Schema(description = "앱 이름", example = "Youtube")
        private String applicationName;
        @Schema(description = "해당 앱의 데이터 사용량", example = "7000")
        private Integer totalUsageData;
    }
}
