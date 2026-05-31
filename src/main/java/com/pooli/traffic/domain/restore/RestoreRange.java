package com.pooli.traffic.domain.restore;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Redis 복구가 대상으로 삼는 업무일 범위이다.
 *
 * @param startInclusive 복구 시작 업무일이며 범위에 포함된다.
 * @param endExclusive 복구 종료 업무일이며 범위에 포함되지 않는다.
 */
public record RestoreRange(
        LocalDate startInclusive,
        LocalDate endExclusive
) {

    /**
     * done log 조회에 사용할 시작 시각을 반환한다.
     */
    public LocalDateTime startDateTimeInclusive() {
        return startInclusive.atStartOfDay();
    }

    /**
     * done log 조회에 사용할 종료 시각을 반환한다.
     */
    public LocalDateTime endDateTimeExclusive() {
        return endExclusive.atStartOfDay();
    }
}
