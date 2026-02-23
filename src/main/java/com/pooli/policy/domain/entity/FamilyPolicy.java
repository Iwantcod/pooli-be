package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 정책 - 가족 매핑 정보 (FAMILY_POLICY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FamilyPolicy {
    private Long familyId;          // PK (복합키)
    private Integer policyId;       // PK (복합키)
    private LocalDateTime createdAt;
}