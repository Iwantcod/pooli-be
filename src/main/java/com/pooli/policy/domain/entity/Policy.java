package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 정책 정보 (POLICY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Policy {
    private Integer policyId;             // PK
    private Integer policyCategoryId;     // FK -> POLICY_CATEGORY
    private String policyName;
    private Boolean isActive;
    private Boolean isNew;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
