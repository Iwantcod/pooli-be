package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "구성원의 특정 앱 정책의 제한 데이터량 수정 요청 DTO")
public class AppDataLimitUpdateReqDto {
    @Schema(description = "수정할 앱 정책의 식별자", example = "10")
    private Long appPolicyId;
    @Schema(description = "새로운 제한 데이터량(Byte 단위)", example = "140000")
    private Long value;
}
