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
    /** 회선 ID */
    private Long lineId;
    /** 가족(공유 풀) ID */
    private Long familyId;
    /** 공유 풀 기여 데이터양 (Byte 단위) */
    private Long amount;
    /** 개인 회선 무제한 요금제 적용 여부 */
    private Boolean individualUnlimited;
    /** 기여도가 반영될 대상 년월 */
    private String targetMonth;
    /** 기여도가 반영될 구체적 대상 날짜 */
    private String usageDate;
}
