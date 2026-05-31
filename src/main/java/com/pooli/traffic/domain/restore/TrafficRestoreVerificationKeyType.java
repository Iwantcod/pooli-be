package com.pooli.traffic.domain.restore;

/**
 * Redis 복구 검증 대상 hash key의 생성 규칙을 구분한다.
 */
public enum TrafficRestoreVerificationKeyType {
    /** 개인 월 잔량 hash key이다. */
    REMAINING_INDIVIDUAL,

    /** 공유풀 월 잔량 hash key이다. */
    REMAINING_SHARED,

    /** 회선 일별 전체 사용량 string counter key이다. */
    DAILY_TOTAL_USAGE,

    /** 회선 일별 앱 사용량 hash key이다. */
    DAILY_APP_USAGE,

    /** 회선 일별 공유 사용량 hash key이다. */
    DAILY_SHARED_USAGE,

    /** 회선 월별 공유 사용량 hash key이다. */
    MONTHLY_SHARED_USAGE,

    /** 전역 정책 활성화 hash key이다. */
    POLICY
}
