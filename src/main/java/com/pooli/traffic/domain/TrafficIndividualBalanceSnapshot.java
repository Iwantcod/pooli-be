package com.pooli.traffic.domain;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개인풀 hydrate에 필요한 RDB 잔량/QoS 스냅샷입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficIndividualBalanceSnapshot {

    /** 회선 고유 식별자 */
    private Long lineId;
    /** 회선별 개인 잔여 데이터량 (Byte 단위) */
    private Long amount;
    /** 데이터 소진 시 적용될 QOS 제한 속도 (bps 단위) */
    private Long qosSpeedLimit;
    /** Redis 잔액 스냅샷이 최종 동기화/갱신된 Epoch 밀리초 시각 */
    private LocalDateTime lastBalanceRefreshedAt;
}
