package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원의 모든 데이터 관련 정책 항목")
public class LimitPolicyResDto {
    @Schema(description = "일별 데이터 사용량 제한 정책 PK", example = "7201")
    private Long dailyLimitId;
    @Schema(description = "일별 데이터 사용 제한량(Byte)", example = "100000")
    private Long dailyDataLimit;
    @Schema(description = "일별 데이터 사용 제한 정책 활성화 여부", example = "true")
    private Boolean isDailyDataLimitActive;

    @Schema(description = "월별 공유풀 데이터 사용 제한 정책 PK", example = "441")
    private Long sharedLimitId;
    @Schema(description = "월별 공유풀 데이터 사용 제한량(Byte)", example = "80000")
    private Long sharedDataLimit;
    @Schema(description = "월별 공유풀 데이터 사용 제한 정책 활성화 여부", example = "true")
    private Boolean isSharedDataLimitActive;
}
