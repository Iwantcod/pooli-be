package com.pooli.traffic.domain.entity;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * TRAFFIC_DEDUCT_DONE 테이블 레코드 매핑 객체입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficDeductDoneLog {

    private Long trafficDeductDoneId;

    private String traceId;
    private String recordId;

    private Long lineId;

    private Long familyId;

    private Integer appId;

    private LocalDateTime enqueuedAt;

    private Long apiTotalData;

    private Long deductedIndividualBytes;

    private Long deductedSharedBytes;

    private Long apiRemainingData;

    private String finalStatus;

    private String lastLuaStatus;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
    private Long latency;

    private String restoreStatus;
    private LocalDateTime restoreStatusUpdatedAt;
    private Integer restoreRetryCount;
    private String restoreLastErrorMessage;

    /**
     * `deducted_total_bytes` 저장 컬럼 제거에 따라 분리 필드 합산값을 파생 반환합니다.
     */
    public Long getDeductedTotalBytes() {
        return safeNonNegative(deductedIndividualBytes) + safeNonNegative(deductedSharedBytes);
    }

    private long safeNonNegative(Long value) {
        if (value == null || value <= 0L) {
            return 0L;
        }
        return value;
    }
}
