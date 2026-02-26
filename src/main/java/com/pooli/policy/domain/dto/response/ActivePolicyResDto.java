package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족에 적용 가능한 활성화 정책")
public class ActivePolicyResDto {
    @Schema(description = "정책 식별자", example = "1001")
    private Long policyId;

    @Schema(description = "정책명", example = "야간 사용 차단")
    private String policyName;

    @Schema(description = "정책 유형", example = "BLOCK")
    private String policyType;

    @Schema(description = "정책 설명", example = "22:00부터 06:00까지 데이터 사용을 차단합니다.")
    private String description;
}
