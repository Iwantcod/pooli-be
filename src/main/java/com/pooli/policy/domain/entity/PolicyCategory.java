package com.pooli.policy.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 정책 카테고리 (POLICY_CATEGORY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyCategory {
    private Integer policyCategoryId;     // PK
    private String policyCategoryName;    // UK
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
