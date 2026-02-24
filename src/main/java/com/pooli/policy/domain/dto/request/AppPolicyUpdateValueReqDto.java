package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "사용자별 앱 정책 값(제한 데이터량 혹은 제한 속도) 수정 요청 DTO")
public class AppPolicyUpdateValueReqDto {
    @Schema(description = "수정할 앱 정책의 식별자", example = "10")
    private Long appDataLimitId;
    @Schema(description = "새로운 값(제한 데이터량(Byte 단위) 혹은 제한 속도(Kbps 단위))", example = "1400")
    private Long value;
}
