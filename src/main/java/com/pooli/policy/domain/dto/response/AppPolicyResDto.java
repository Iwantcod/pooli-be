package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 회선의 앱 단위 정책 상세")
public class AppPolicyResDto {
    @Schema(description = "앱 식별자", example = "301")
    private Long appId;

    @Schema(description = "앱 이름", example = "YouTube")
    private String appName;

    @Schema(description = "정책 유형", example = "LIMIT")
    private String policyType;

    @Schema(description = "앱 정책 활성화 여부", example = "true")
    private Boolean enabled;

    @Schema(description = "앱 일일 제한량(MB)", example = "500")
    private Integer dailyLimitMb;
}
