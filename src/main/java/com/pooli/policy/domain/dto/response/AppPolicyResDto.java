package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "특정 회선의 앱 단위 정책 상세")
public record AppPolicyResDto(
        @Schema(description = "앱 식별자", example = "301")
        Long appId,
        @Schema(description = "앱 이름", example = "YouTube")
        String appName,
        @Schema(description = "정책 유형", example = "LIMIT")
        String policyType,
        @Schema(description = "앱 정책 활성화 여부", example = "true")
        Boolean enabled,
        @Schema(description = "앱 일일 제한(MB)", example = "500")
        Integer dailyLimitMb
) {
    public static AppPolicyResDto of(
            Long appId,
            String appName,
            String policyType,
            Boolean enabled,
            Integer dailyLimitMb
    ) {
        return new AppPolicyResDto(appId, appName, policyType, enabled, dailyLimitMb);
    }
}

