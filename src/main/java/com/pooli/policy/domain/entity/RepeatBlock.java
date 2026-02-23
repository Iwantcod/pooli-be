package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 반복적 차단 정보 (REPEAT_BLOCK)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RepeatBlock {
    private Long repeatBlockId;     // PK
    private Long lineId;            // FK -> LINE
    private Boolean isActive;       // 활성화 여부
    private Integer startAt;        // 24시 표기 (예: 13 -> 13시)
    private Integer endAt;          // 24시 표기
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}