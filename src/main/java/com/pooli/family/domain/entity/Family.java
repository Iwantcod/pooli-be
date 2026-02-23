package com.pooli.family.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family {

    private Long familyId;

    private Long poolBaseData;
    private Long poolTotalData;
    private Long poolRemainingData;

    private Long familyThreshold;
    private Boolean isThresholdActive;

    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}