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
    /** 알림 식별용 UUID */
    private String uuid;
    /** 가족 ID */
    private Long familyId;
    /** 공유 풀 경고 임계 비율 (예: 80%의 경우 80) */
    private Integer thresholdPct;
    /** 정책 적용 년월 */
    private String targetMonth;
    /** 임계치 동기화 요청 Epoch 밀리초 시각 */
    private Long createdAtEpochMillis;
}
