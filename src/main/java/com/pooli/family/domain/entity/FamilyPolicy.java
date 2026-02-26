package com.pooli.family.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FamilyPolicy {

    private Long familyId;
    private Integer policyId;

    private LocalDateTime createdAt;
}