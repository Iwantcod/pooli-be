package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "사용자별 앱 정책 활성화(or 신규생성)/비활성화 요청 dto")
public class AppPolicyActiveToggleReqDto {
    @Schema(description = "회선 식별자", example = "1")
    private Long lineId;
    @Schema(description = "앱 식별자", example = "10")
    private Integer applicationId;
}
