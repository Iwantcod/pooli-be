package com.pooli.family.domain.entity;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FamilySharedUsageDaily {

    private LocalDate usageDate;

    private Long familyId;
    private Long lineId;

    private Long usageAmount;
    private Long contributionAmount;

    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}