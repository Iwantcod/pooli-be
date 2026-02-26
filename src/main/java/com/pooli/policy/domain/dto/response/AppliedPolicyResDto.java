package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "적용 정책 상세")
public class AppliedPolicyResDto {

    @Schema(description = "데이터 사용 즉시 차단 정책 정보")
    private ImmediateBlockResDto immediateBlock;

    @Schema(description = "데이터 반복적 차단 정책 목록")
    private List<RepeatBlockPolicyResDto> repeatBlockPolicyList;

    @Schema(description = "데이터 사용 제한 정책 정보")
    private LimitPolicyResDto limitPolicy;

    @Schema(description = "앱 정책 목록")
    private List<AppPolicyResDto> appPolicyList;

}
