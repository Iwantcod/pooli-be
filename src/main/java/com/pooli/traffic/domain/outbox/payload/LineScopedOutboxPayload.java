package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * line 식별자 기반 재조회가 필요한 이벤트 payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineScopedOutboxPayload {
    /** 회선 ID */
    private Long lineId;
    /** 회선 범위 동기화 설정 버전 */
    private Long version;
}
