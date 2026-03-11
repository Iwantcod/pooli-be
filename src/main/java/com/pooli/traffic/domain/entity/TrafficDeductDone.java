package com.pooli.traffic.domain.entity;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * TRAFFIC_DEDUCT_DONE 테이블의 레코드를 표현하는 엔티티입니다.
 * traceId 기준 완료 이력(DONE) 영속화와 idempotency 판정에 사용합니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficDeductDone {

    private Long trafficDeductDoneId;
    private String traceId;
    private Long lineId;
    private Long familyId;
    private Integer appId;
    private Long apiTotalData;
    private Long deductedTotalBytes;
    private Long apiRemainingData;
    private String finalStatus;
    private String lastLuaStatus;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private LocalDateTime persistedAt;
}
