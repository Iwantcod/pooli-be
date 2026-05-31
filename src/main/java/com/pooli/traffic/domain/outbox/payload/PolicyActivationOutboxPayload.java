package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정책 활성화 동기화 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyActivationOutboxPayload {
    /** 활성화/비활성화 대상 정책 ID */
    private Long policyId;
    /** 정책의 활성화 상태 값 */
    private Boolean active;
    /** 정책 버전 정보 */
    private Long version;
}
