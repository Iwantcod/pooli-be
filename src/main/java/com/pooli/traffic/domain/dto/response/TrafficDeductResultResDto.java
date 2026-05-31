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
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficDeductResultResDto {
    /** 요청 Trace ID */
    private final String traceId;
    /** 최초 요청한 트래픽 총량 (Byte 단위) */
    private final Long apiTotalData;
    /** 개인 풀 차감 바이트 수 */
    private final Long deductedIndividualBytes;
    /** 가족 공유 풀 차감 바이트 수 */
    private final Long deductedSharedBytes;
    /** QoS 제어 하에 차감된 바이트 수 */
    private final Long deductedQosBytes;
    /** 차감 완료 후 최종 남은 API 잔여 데이터량 (Byte 단위) */
    private final Long apiRemainingData;
    /** 트래픽 처리 최종 상태 코드 */
    private final TrafficFinalStatus finalStatus;
    /** 최종 수행된 Lua 스크립트 결과 상태 */
    private final TrafficLuaStatus lastLuaStatus;
    /** 차감 실패 시 원인 코드 */
    private final String failureReason;
    /** 트래픽 처리 기록 생성 시각 */
    private final LocalDateTime createdAt;
    /** 트래픽 차감 처리 완료 시각 */
    private final LocalDateTime finishedAt;

    /**
     * `deductedTotalBytes` 저장 필드는 두지 않고, 개인/공유/QoS 분리 필드 합산값을 파생 반환합니다.
     */
    public Long getDeductedTotalBytes() {
        return safeNonNegative(deductedIndividualBytes)
                + safeNonNegative(deductedSharedBytes)
                + safeNonNegative(deductedQosBytes);
    }

    /** 0 이상의 유효한 바이트 수를 보장하기 위한 헬퍼 메서드 */
    private long safeNonNegative(Long value) {
        if (value == null || value <= 0L) {
            return 0L;
        }
        return value;
    }
}
