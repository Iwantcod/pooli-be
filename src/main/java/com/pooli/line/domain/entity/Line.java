package com.pooli.line.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Line {

    private Long lineId;
    private Long userId;
    private Integer planId;

    private String phone;

    private LocalDateTime blockEndAt;

    private Long remainingData;

    private Boolean isMain;

    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;

    private Long individualThreshold;
    private Boolean isThresholdActive;
}