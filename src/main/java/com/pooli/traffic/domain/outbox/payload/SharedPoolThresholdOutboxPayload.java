package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유풀 잔량 임계치 도달 알람 재시도에 필요한 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SharedPoolThresholdOutboxPayload {
    private String uuid;
    private Long familyId;
    private Integer thresholdPct;
    private String targetMonth;
    private Long createdAtEpochMillis;
}
