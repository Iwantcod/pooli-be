package com.pooli.data.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@Schema(description = "앱 서비스별 데이터 사용량 조회 응답 DTO")
public class AppDataUsageResDto {

    @Schema(description = "총 사용 데이터량(MB)", example = "5200")
    private Long totalUsedAmount;

    @Schema(description = "앱별 사용량 목록")
    private List<AppUsageDto> apps;

    @Getter
    @Builder
    @Schema(description = "앱별 사용량 DTO")
    public static class AppUsageDto {

        @Schema(description = "애플리케이션 이름", example = "YouTube")
        private String appName;

        @Schema(description = "사용량(MB)", example = "2000")
        private Long usedAmount;
    }
}