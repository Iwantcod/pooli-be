package com.pooli.data.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "공유 및 개인 데이터 잔량 조회 응답 DTO")
public class DataBalancesResDto {

    @Schema(description = "사용자 이름", example = "홍길동")
    private String userName;

    @Schema(description = "가족 공유 데이터 잔량(MB)", example = "5000")
    private Long sharedDataRemaining;

    @Schema(description = "개인 데이터 잔량(MB)", example = "2000")
    private Long personalDataRemaining;

    @Schema(description = "이용 중인 요금제 명", example = "5G 프리미엄")
    private String planName;
}