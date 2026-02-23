package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 앱 속도 제한 (APP_SPEED_LIMIT)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppSpeedLimit {
    private Long appSpeedLimitId;   // PK
    private Long lineId;            // FK -> LINE
    private Integer applicationId;  // FK -> Application
    private Integer speedLimit;     // 단위: Kbps
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
