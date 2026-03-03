package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 회선 데이터 사용 한도 정책 (LINE_LIMIT)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineLimit {
    private Long limitId;                   // PK
    private Long lineId;                    // FK -> LINE
    private Long dailyDataLimit;            // 단위: Byte
    private Boolean is_daily_limit_active;  // 일별 개인풀 사용 제한 정책 활성화 여부
    private Long sharedDataLimit;           // 단위: Byte
    private Boolean is_shared_limit_active; // 월별 공유풀 사용 제한 정책 활성화 여부
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
