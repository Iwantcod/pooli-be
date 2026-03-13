package com.pooli.family.domain.dto.response;

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
@Schema(description = "공유 데이터 히스토리 응답 DTO")
public class SharedPoolHistoryItemResDto {

    @Schema(description = "이벤트 타입", example = "USAGE")
    private String eventType;

    @Schema(description = "표시 타이틀", example = "데이터 사용")
    private String title;

    @Schema(description = "사용자 이름", example = "김영희")
    private String userName;

    @Schema(description = "발생 시각 또는 날짜", example = "2026-03-15")
    private String occurredAt;

    @Schema(description = "데이터 양(Byte)", example = "1200000000")
    private Long amount;

    @Schema(description = "시간 정밀도", example = "DAY")
    private String precision;
}
