package com.pooli.family.domain.entity;

import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SharedPoolDomain {

    // FAMILY 테이블
    private Long familyId;
    private Long poolBaseData;
    private Long poolTotalData;
    private Long poolRemainingData;
    private Long familyThreshold;
    private Boolean isThresholdActive;

    // FAMILY_SHARED_USAGE_DAILY 테이블 (집계)
    private Long monthlyUsageAmount;        // 당월 사용량 합계
    private Long monthlyContributionAmount; // 당월 충전량 합계

    // LINE 테이블 (개인 조회용)
    private Long lineId;
    private Long remainingData;             // 개인 데이터 잔여량
    private Long personalContribution;      // 내가 담은 양

    // PLAN 테이블 (JOIN)
    private Long basicDataAmount;
}
