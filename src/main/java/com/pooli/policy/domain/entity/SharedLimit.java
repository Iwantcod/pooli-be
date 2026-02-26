package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 회선별 공유풀 사용량 한도 (SHARED_LIMIT)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SharedLimit {
    private Long sharedLimitId;      // PK
    private Long lineId;             // FK -> LINE
    private Long sharedDataLimit;    // 단위: Byte
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
