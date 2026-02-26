package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 정책 화이트 리스트 (WHITELIST)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Whitelist {
    private Long whitelistId;       // PK
    private Long lineId;            // FK -> LINE
    private Integer applicationId;  // FK -> Application
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
