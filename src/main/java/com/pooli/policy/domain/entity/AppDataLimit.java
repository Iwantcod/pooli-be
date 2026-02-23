package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 앱 데이터 사용 한도 (APP_DATA_LIMIT)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppDataLimit {
    private Long appDataLimitId;    // PK
    private Long lineId;            // FK -> LINE
    private Integer applicationId;  // FK -> Application
    private Long dataLimit;         // 단위: Byte
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}