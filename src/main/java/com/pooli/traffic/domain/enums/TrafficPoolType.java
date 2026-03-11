package com.pooli.traffic.domain.enums;

/**
 * 트래픽 차감 대상 풀 유형을 표현합니다.
 * HYDRATE/REFILL 어댑터에서 개인풀/공유풀 분기 처리에 사용합니다.
 */
public enum TrafficPoolType {
    INDIVIDUAL,
    SHARED
}
