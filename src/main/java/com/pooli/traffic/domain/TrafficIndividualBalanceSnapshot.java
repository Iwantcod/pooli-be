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

    private Long lineId;
    private Long amount;
    private Long qosSpeedLimit;
    private LocalDateTime lastBalanceRefreshedAt;
}
