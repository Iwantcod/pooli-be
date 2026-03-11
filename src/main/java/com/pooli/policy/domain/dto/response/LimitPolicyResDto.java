package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원의 모든 데이터 관련 정책 항목")
public class LimitPolicyResDto {
    @Schema(description = "회선의 데이터 사용량 제한 정책 PK", example = "7201")
    private Long lineLimitId;

    @Schema(description = "일별 데이터 사용 제한량(Byte)", example = "100000")
    private Long dailyDataLimit;
    @Schema(description = "일별 데이터 사용 제한 정책 활성화 여부", example = "true")
    private Boolean isDailyDataLimitActive;

    @Schema(description = "월별 공유풀 데이터 사용 제한량(Byte)", example = "80000")
    private Long sharedDataLimit;
    @Schema(description = "월별 공유풀 데이터 사용 제한 정책 활성화 여부", example = "true")
    private Boolean isSharedDataLimitActive;

    @Schema(description = "가족 공유풀 제한 한도(Byte)", example = "10737418240")
    private Long maxSharedData;

    @Schema(description = "개인 데이터 제한 한도(Byte)", example = "10737418240")
    private Long maxDailyData;
}
