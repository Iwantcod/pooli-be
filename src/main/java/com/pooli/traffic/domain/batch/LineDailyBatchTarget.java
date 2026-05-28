package com.pooli.traffic.domain.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LINE_DAILY_BATCH_TARGET 한 row를 표현한다.
 * usage_date + line_id unique key로 같은 날짜의 target row 중복 생성을 막는다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineDailyBatchTarget {

    /** LINE_DAILY_BATCH_TARGET row 내부 식별자 */
    private Long id;

    /** 배치 처리 대상 사용량 기준 일자 */
    private LocalDate usageDate;

    /** 처리 대상 LINE row 식별자 */
    private Long lineId;

    /** 현재 target row 처리 상태 */
    private LineDailyBatchTargetStatus status;

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
