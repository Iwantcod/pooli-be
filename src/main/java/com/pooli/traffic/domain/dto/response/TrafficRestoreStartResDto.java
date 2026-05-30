package com.pooli.traffic.domain.dto.response;

import java.time.LocalDate;

/**
 * 관리자 Redis 복구 시작 응답이다.
 *
 * @param accepted 복구 시작 요청이 접수됐는지 여부
 * @param nextPhase 요청 접수 후 상태 또는 다음 처리 상태
 * @param failureDate Redis 장애가 발생해 복구 anchor로 삼은 업무일
 * @param restoreStartDate 서버가 계산한 미완료 데이터 복구 시작 업무일
 */
public record TrafficRestoreStartResDto(
        boolean accepted,
        String nextPhase,
        LocalDate failureDate,
        LocalDate restoreStartDate
) {
}
