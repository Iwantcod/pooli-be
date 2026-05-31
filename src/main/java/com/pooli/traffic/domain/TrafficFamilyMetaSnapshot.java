package com.pooli.traffic.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유풀 임계치 판정에 필요한 FAMILY 메타 스냅샷입니다.
 *
 * <p>실시간 잔량은 Redis balance snapshot의 amount 필드에서 별도로 조회합니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficFamilyMetaSnapshot {

    /** 공유 풀을 공유하는 가족(Family) 그룹 식별자 */
    private Long familyId;
    /** 가족 공유 풀에 할당된 전체 데이터량 (Byte 단위) */
    private Long poolTotalData;
    /** 가족 공유 풀 사용량 경고/알림 임계값 비율 */
    private Long familyThreshold;
    /** 가족 공유 풀 임계값 검증 정책의 활성화 여부 */
    private Boolean thresholdActive;
}
