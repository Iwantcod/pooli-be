package com.pooli.traffic.domain.restore;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RESTORE_HYDRATE_TARGET 한 row를 표현한다.
 * phase 0 worker는 월, target 종류, 소유자 식별자 기준으로 Redis hydrate 대상을 처리한다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRestoreHydrateTarget {

    /** RESTORE_HYDRATE_TARGET row 내부 식별자 */
    private Long id;

    /** 이 target이 속한 복구 batch 이름 */
    private String batchName;

    /** hydrate 대상 월의 1일 */
    private LocalDate targetMonthStart;

    /** hydrate 대상 Redis key 종류 */
    private TrafficRestoreHydrateTargetType targetType;

    /** line_id, family_id, 또는 global policy용 0 값 */
    private Long targetOwnerId;

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
