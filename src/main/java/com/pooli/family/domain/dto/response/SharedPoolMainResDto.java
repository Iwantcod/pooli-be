package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "메인 대시보드 공유풀 잔량 조회 응답 DTO")
public class SharedPoolMainResDto {

    @Schema(description = "당월 공유풀 기본 충전량(MB)", example = "20000")
    private Long sharedPoolBaseData;

    @Schema(description = "당월 공유풀 추가 충전량(MB)", example = "5000")
    private Long sharedPoolAdditionalData;

    @Schema(description = "당월 공유풀 잔여량(MB)", example = "12000")
    private Long sharedPoolRemainingData;

    @Schema(description = "당월 공유풀 총량(MB)", example = "25000")
    private Long sharedPoolTotalData;
}