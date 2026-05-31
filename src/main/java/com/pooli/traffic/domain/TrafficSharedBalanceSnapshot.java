package com.pooli.traffic.domain;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유풀 hydrate에 필요한 RDB 잔량 스냅샷입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficSharedBalanceSnapshot {

    /** 가족(공유 풀) 식별자 */
    private Long familyId;
    /** 가족 공유 풀의 잔여 데이터량 (Byte 단위) */
    private Long amount;
    /** 공유 풀 Redis 잔액 스냅샷이 최종 갱신된 Epoch 밀리초 시각 */
    private LocalDateTime lastBalanceRefreshedAt;
}
