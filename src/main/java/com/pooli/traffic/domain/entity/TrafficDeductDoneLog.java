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

    /** 영속 로그 레코드 식별자 PK */
    private Long trafficDeductDoneId;

    /** 요청 추적용 고유 Trace ID */
    private String traceId;
    /** 이벤트를 기록한 DB 레코드 ID */
    private String recordId;

    /** 대상 회선 ID */
    private Long lineId;

    /** 대상 가족 ID */
    private Long familyId;

    /** 애플리케이션 ID */
    private Integer appId;

    /** 최초 요청 큐 적재 시각 */
    private LocalDateTime enqueuedAt;

    /** 요청 트래픽 총량 (Byte 단위) */
    private Long apiTotalData;

    /** 개인 풀 차감 바이트 수 */
    private Long deductedIndividualBytes;

    /** 가족 공유 풀 차감 바이트 수 */
    private Long deductedSharedBytes;

    /** QoS 적용 차감 바이트 수 */
    private Long deductedQosBytes;

    /** 최종 잔여 데이터량 (Byte 단위) */
    private Long apiRemainingData;

    /** 트래픽 처리 최종 완료 상태 */
    private String finalStatus;

    /** 마지막 실행된 Lua 스크립트 상태 */
    private String lastLuaStatus;

    /** 차감 실패 원인 정보 */
    private String failureReason;

    /** 엔티티 로그 생성 시각 */
    private LocalDateTime createdAt;
    /** 차감 처리 시작 시각 */
    private LocalDateTime startedAt;

    /** 차감 처리 종료 시각 */
    private LocalDateTime finishedAt;
    /** 처리 소요 시간 (밀리초 단위) */
    private Long latency;

    /** 트래픽 복구(Restore) 상태 정보 */
    private String restoreStatus;
    /** 복구 상태 최종 업데이트 시각 */
    private LocalDateTime restoreStatusUpdatedAt;
    /** 복구 재시도 횟수 */
    private Integer restoreRetryCount;
    /** 복구 실패 시 마지막 에러 메시지 */
    private String restoreLastErrorMessage;

    /**
     * `deducted_total_bytes` 저장 컬럼 제거에 따라 분리 필드 합산값을 파생 반환합니다.
     */
    public Long getDeductedTotalBytes() {
        return safeNonNegative(deductedIndividualBytes)
                + safeNonNegative(deductedSharedBytes)
                + safeNonNegative(deductedQosBytes);
    }

    /** 0 이상의 유효한 데이터를 반환하기 위한 헬퍼 메서드 */
    private long safeNonNegative(Long value) {
        if (value == null || value <= 0L) {
            return 0L;
        }
        return value;
    }
}
