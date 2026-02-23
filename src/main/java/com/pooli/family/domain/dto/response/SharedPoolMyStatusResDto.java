package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "공유 데이터 담기 화면 개인 정보 조회 응답 DTO")
public class SharedPoolMyStatusResDto {

    @Schema(description = "개인 데이터 잔여량(bite)", example = "3500")
    private Long remainingData;

    @Schema(description = "내가 총 공유 데이터 담은 양(bite)", example = "1200")
    private Long contributionAmount;
}