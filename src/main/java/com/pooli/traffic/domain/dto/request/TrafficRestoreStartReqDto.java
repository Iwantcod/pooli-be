package com.pooli.traffic.domain.dto.request;

import java.time.LocalDate;

/**
 * 관리자 Redis 복구 시작 요청이다.
 *
 * @param failureDate Redis 장애가 발생해 복구 anchor로 삼을 업무일
 */
public record TrafficRestoreStartReqDto(
        LocalDate failureDate
) {
}
