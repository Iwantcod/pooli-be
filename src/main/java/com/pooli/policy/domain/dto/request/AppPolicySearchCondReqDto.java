package com.pooli.policy.domain.dto.request;

import com.pooli.policy.domain.enums.PolicyScope;
import com.pooli.policy.domain.enums.SortType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원 앱 정책 동적 조회 조건")
public class AppPolicySearchCondReqDto {
    @Schema(description = "회선 식별자", example = "101")
    private Long lineId;

    @Schema(description = "앱 이름 검색 키워드", example = "You")
    private String keyword;

    @Schema(description = "정책 범위 필터", example = "ALL", allowableValues = {"ALL", "APPLIED", "NONE", "WHITELIST"})
    private PolicyScope policyScope;

    @Schema(description = "데이터 제한 설정된 앱만 조회(필터 활성화:true)", example = "false")
    private boolean dataLimit;

    @Schema(description = "속도 제한 설정된 앱만 조회(필터 활성화:true)", example = "false")
    private boolean speedLimit;

    @Schema(description = "정렬 기준", example = "ACTIVE", allowableValues = {"ACTIVE", "NAME"})
    private SortType sortType;

    @Schema(description = "조회할 페이지 번호(0부터 시작)", example = "0")
    private Integer pageNumber;

    @Schema(description = "페이지 당 크기", example = "10")
    private Integer pageSize;

    private Integer offset;
}
