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

    /** 개인 풀에서 차감된 데이터양 (Byte 단위) */
    private final long indivDeducted;
    /** 가족 공유 풀에서 차감된 데이터양 (Byte 단위) */
    private final long sharedDeducted;
    /** QoS 제어 조건 하에 차감된 데이터양 (Byte 단위) */
    private final long qosDeducted;
    /** 스크립트 실행이 완료된 Epoch 밀리초 시각 */
    private final Long finishedAtEpochMillis;
    /** Lua 스크립트 실행 결과 상태 정보 */
    private final TrafficLuaStatus status;
    /** 정책 검증 실패 또는 에러 발생 시 상세 원인 코드 */
    private final String failureReason;

    /** 개인, 공유, QoS 풀 전체에서 차감된 총 데이터 합계를 반환합니다. */
    public long getTotalDeducted() {
        return safeNonNegative(indivDeducted)
                + safeNonNegative(sharedDeducted)
                + safeNonNegative(qosDeducted);
    }

    /** 음수 값을 방지하고 최소 0 이상의 값을 보장하기 위한 유틸리티 메서드 */
    private long safeNonNegative(long value) {
        return Math.max(0L, value);
    }
}
