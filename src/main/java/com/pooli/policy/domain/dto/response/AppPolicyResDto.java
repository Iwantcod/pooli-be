package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원의 앱 정책 항목")
public class AppPolicyResDto {
    @Schema(description = "앱 정책 PK", example = "7301")
    private Long appPolicyId;

    @Schema(description = "앱 식별자", example = "301")
    private Integer appId;

    @Schema(description = "앱 이름", example = "YouTube")
    private String appName;

    @Schema(description = "정책 활성화 여부", example = "true")
    private Boolean enabled;

    @Schema(description = "일일 제한량(MB)", example = "500")
    private Integer dailyLimitMb;

    @Schema(description = "제한 속도(Kbps, 1Mbps: 1000Kbps)", example = "5000")
    private Integer dailyLimitSpeed;
}
