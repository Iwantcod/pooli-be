package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유풀 기여 Redis-first 복구에 필요한 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SharedPoolContributionOutboxPayload {
    private Long lineId;
    private Long familyId;
    private Long amount;
    private Boolean individualUnlimited;
    private String targetMonth;
    private String usageDate;
}
