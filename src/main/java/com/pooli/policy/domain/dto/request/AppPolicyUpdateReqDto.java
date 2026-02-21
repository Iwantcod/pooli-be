package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "앱 정책 단건 수정 요청")
public class AppPolicyUpdateReqDto {
    @Schema(description = "앱 정책 활성화 여부", example = "true")
    private Boolean enabled;

    @Schema(description = "일일 제한량(MB)", example = "500")
    private Integer dailyLimitMb;
}
