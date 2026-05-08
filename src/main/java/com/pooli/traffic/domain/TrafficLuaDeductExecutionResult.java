package com.pooli.traffic.domain;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.Builder;
import lombok.Getter;

/**
 * 통합 차감 Lua가 반환한 출처별 차감량과 최종 상태를 담는 결과 객체입니다.
 */
@Getter
@Builder
public class TrafficLuaDeductExecutionResult {

    private final long indivDeducted;
    private final long sharedDeducted;
    private final long qosDeducted;
    private final TrafficLuaStatus status;

    public long getTotalDeducted() {
        return safeNonNegative(indivDeducted)
                + safeNonNegative(sharedDeducted)
                + safeNonNegative(qosDeducted);
    }

    private long safeNonNegative(long value) {
        return Math.max(0L, value);
    }
}
