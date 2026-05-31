package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앱 정책 단건 동기화 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppPolicyOutboxPayload {
    /** 회선 ID */
    private Long lineId;
    /** 애플리케이션 ID */
    private Integer appId;
    /** 정책 버전 번호 */
    private Long version;
}
