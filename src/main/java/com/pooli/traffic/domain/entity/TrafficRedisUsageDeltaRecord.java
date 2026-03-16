package com.pooli.traffic.domain.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.domain.enums.TrafficRedisUsageDeltaStatus;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DB fallback 동안 발생한 Redis usage 불일치 보정을 위한 저장 레코드입니다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRedisUsageDeltaRecord {

    private Long id;
    private String traceId;
    private TrafficPoolType poolType;
    private Long lineId;
    private Long familyId;
    private Integer appId;
    private Long usedBytes;
    private LocalDate usageDate;
    private String targetMonth;
    private TrafficRedisUsageDeltaStatus status;
    private Integer retryCount;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime statusUpdatedAt;
}
