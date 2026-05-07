package com.pooli.traffic.domain.dto.response;

import java.time.LocalDateTime;

import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * traffic 서버가 차감 처리를 완료한 뒤 영속 저장/응답 전달에 사용하는 결과 DTO입니다.
 * 최종 상태, 총 차감량, 마지막 Lua 상태를 함께 전달합니다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficDeductResultResDto {
    private final String traceId;
    private final Long apiTotalData;
    private final Long deductedIndividualBytes;
    private final Long deductedSharedBytes;
    private final Long apiRemainingData;
    private final TrafficFinalStatus finalStatus;
    private final TrafficLuaStatus lastLuaStatus;
    private final LocalDateTime createdAt;
    private final LocalDateTime finishedAt;

    /**
     * `deductedTotalBytes` 저장 필드는 두지 않고, 개인/공유 분리 필드 합산값을 파생 반환합니다.
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
