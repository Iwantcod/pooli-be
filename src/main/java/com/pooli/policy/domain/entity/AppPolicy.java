package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 회선 별 앱 데이터 사용 정책 (APP_POLICY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppPolicy {
    private Long appPolicyId;   // PK
    private Long lineId;            // FK -> LINE
    private Integer applicationId;  // FK -> Application
    private Long dataLimit;         // 단위: Byte, -1: 무제한을 의미
    private Integer speedLimit;     // 단위: Kbps, -1: 무제한을 의미
    private Boolean isActive;       // 해당 앱 정책 활성화 여부
    private Boolean isWhitelist;    // 정책 화이트리스트 적용 여부
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
