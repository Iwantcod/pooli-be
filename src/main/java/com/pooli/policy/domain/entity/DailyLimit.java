package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 회선 별 하루 데이터 사용 한도 (DAILY_LIMIT)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyLimit {
    private Long dailyLimitId;       // PK
    private Long lineId;             // FK -> LINE
    private Long dailyDataLimit;     // 단위: Byte
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
