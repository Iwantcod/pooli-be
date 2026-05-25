package com.pooli.traffic.domain.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LINE_DAILY_BATCH_JOB 한 row를 표현한다.
 * manager/worker는 이 metadata row의 상태와 count를 기준으로 배치 진행 가능 여부와 완료 여부를 판단한다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LineDailyBatchJob {

    /** LINE_DAILY_BATCH_JOB row 내부 식별자 */
    private Long id;

    /** 실행 단계 구분값(target 생성 batch / usage sync batch) */
    private BatchName batchName;

    /** 배치 처리 대상 사용량 기준 일자 */
    private LocalDate usageDate;

    /** 현재 metadata row 진행 상태 */
    private LineDailyBatchStatus status;

    /** status 최종 변경 시각 */
    private LocalDateTime statusUpdatedAt;

    /** batch가 RUNNING으로 전환된 시각 */
    private LocalDateTime runStartedAt;

    /** batch가 terminal 상태로 종료된 시각 */
    private LocalDateTime finishedAt;

    /** 처리 대상 line 수(두 batch 모두 line 단위) */
    private Long targetCount;

    /** DONE 또는 target 확보 성공으로 집계된 line 수 */
    private Long successCount;

    /** FAILED로 최종 실패 처리된 line 수 */
    private Long failedCount;

    /** Redis 사용량 key가 없어 SKIPPED 처리된 line 수 */
    private Long skippedCount;

    /** success/failed/skipped count 최종 변경 시각 */
    private LocalDateTime processedCountUpdatedAt;

    /** batch 실행을 시작한 manager 인스턴스 식별자 */
    private String managerInstanceId;

    /** 마지막 실패 분류 코드 */
    private String lastErrorCode;

    /** 마지막 실패 상세 메시지 */
    private String lastErrorMessage;

    /** metadata row 생성 시각 */
    private LocalDateTime createdAt;

    /** metadata row 최종 수정 시각 */
    private LocalDateTime updatedAt;
}
