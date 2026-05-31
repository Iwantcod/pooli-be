package com.pooli.data.domain.entity;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyAppTotalData {
    private LocalDate usageDate;

    private Long lineId;
    private Integer applicationId;

    private Long individualUsageData;
    private Long sharedUsageData;
    private Long qosUsageData;

    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}
