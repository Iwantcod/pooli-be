package com.pooli.traffic.domain;

import lombok.Builder;
import lombok.Getter;

/**
 * 리필 의사결정에 필요한 계산 결과를 담는 값 객체입니다.
 */
@Getter
@Builder
public class TrafficRefillPlan {

    private final Long delta;
    private final Integer bucketCount;
    private final Long bucketSum;
    private final Long refillUnit;
    private final Long threshold;
    private final String source;
}
