package com.pooli.traffic.domain.dto.response;

import java.time.LocalDate;

/**
 * 관리자 Redis 복구 재개 응답이다.
 *
 * @param anchorDate 재개 확인 대상 복구 anchor 업무일
 * @param resumeAccepted 재개 가능한 상태인지 여부
 * @param currentStatus 현재 확인된 복구 phase metadata 상태
 */
public record TrafficRestoreResumeResDto(
        LocalDate anchorDate,
        boolean resumeAccepted,
        String currentStatus
) {
}
