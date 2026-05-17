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

    private Long familyId;
    private Long poolTotalData;
    private Long familyThreshold;
    private Boolean thresholdActive;
}
