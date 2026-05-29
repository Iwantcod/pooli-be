package com.pooli.traffic.domain.dto.response;

/**
 * 관리자 Redis 복구 시작 응답이다.
 *
 * @param accepted 복구 시작 요청이 접수됐는지 여부
 * @param nextPhase 다음으로 처리할 복구 phase 이름
 */
public record TrafficRestoreStartResDto(
        boolean accepted,
        String nextPhase
) {
}
