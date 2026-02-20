package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "회선 정책 일괄 수정 요청 바디")
public record LinePolicyUpdateReqDto(
        @Schema(
                description = "회선 차단 정책 사용 여부",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Boolean blockPolicyEnabled,
        @Schema(
                description = "일일 데이터 제한(MB)",
                example = "1024",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Integer dailyLimitMb,
        @Schema(
                description = "허용할 앱 정책 식별자 목록",
                example = "[11, 12, 13]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<Long> allowedAppPolicyIds
) {
}

