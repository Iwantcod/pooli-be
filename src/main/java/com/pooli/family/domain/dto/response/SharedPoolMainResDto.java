package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "메인 대시보드 공유풀 잔량 조회 응답 DTO")
public class SharedPoolMainResDto {

    @Schema(description = "당월 공유풀 기본 충전량(bite)", example = "20000")
    private Long sharedPoolBaseData;

    @Schema(description = "당월 공유풀 추가 충전량(bite)", example = "5000")
    private Long sharedPoolAdditionalData;

    @Schema(description = "당월 공유풀 잔여량(bite)", example = "12000")
    private Long sharedPoolRemainingData;

    @Schema(description = "당월 공유풀 총량(bite)", example = "25000")
    private Long sharedPoolTotalData;
}