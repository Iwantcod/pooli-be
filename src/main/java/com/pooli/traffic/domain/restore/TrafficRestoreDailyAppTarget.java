package com.pooli.traffic.domain.restore;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RESTORE_DAILY_APP_TARGET 한 row를 표현한다.
 * phase 1 worker는 usage_date, line_id, application_id 기준으로 Redis replay 대상을 처리한다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRestoreDailyAppTarget {

    /** RESTORE_DAILY_APP_TARGET row 내부 식별자 */
    private Long id;

    /** 이 target이 속한 복구 batch 이름 */
    private String batchName;

    /** DAILY_APP_TOTAL_DATA 사용량 기준 일자 */
    private LocalDate usageDate;

    /** 처리 대상 LINE row 식별자 */
    private Long lineId;

    /** 처리 대상 application 식별자 */
    private Integer applicationId;

    /** 현재 target row 처리 상태 */
    private TrafficRestoreTargetStatus status;

    /** status 최종 변경 시각 */
    private LocalDateTime statusUpdatedAt;

    /** PROCESSING 상태로 선점한 worker 식별자 */
    private String workerId;

    /** RETRYABLE로 되돌린 횟수 */
    private Integer retryCount;

    /** 마지막 실패 분류 코드 */
    private String lastErrorCode;

    /** 마지막 실패 상세 메시지 */
    private String lastErrorMessage;

    /** target row 생성 시각 */
    private LocalDateTime createdAt;
}
