package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 즉시 차단 종료시각 동기화 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ImmediateBlockOutboxPayload {
    /** 회선 ID */
    private Long lineId;
    /** 차단 해제 예정 Epoch 초 단위 시각 */
    private Long blockEndEpochSecond;
    /** 차단 정책 버전 */
    private Long version;
}
