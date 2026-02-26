package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "사용자별 앱 정책 생성")
public class AppPolicyCreateReqDto {
    @Schema(description = "정책을 적용할 회선 식별자", example = "1")
    private Long lineId;
    @Schema(description = "정책을 적용할 앱 식별자", example = "10")
    private Integer applicationId;
}
