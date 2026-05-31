package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * line_limit 동기화 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineLimitOutboxPayload {
    /** 회선 ID */
    private Long lineId;
    /** 회선 일일 기본 한도 (Byte 단위) */
    private Long dailyLimit;
    /** 일일 한도 차감 정책의 활성화 상태 */
    private Boolean isDailyActive;
    /** 회선 공유 풀 한도 (Byte 단위) */
    private Long sharedLimit;
    /** 공유 풀 한도 차감 정책의 활성화 상태 */
    private Boolean isSharedActive;
    /** 제한 정보 설정 버전 */
    private Long version;
}
