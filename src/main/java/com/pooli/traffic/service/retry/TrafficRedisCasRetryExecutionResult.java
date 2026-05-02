package com.pooli.traffic.service.retry;

import org.springframework.dao.DataAccessException;

/**
 * Redis CAS retry 실행 결과를 호출부로 전달하는 불변 DTO입니다.
 */
public record TrafficRedisCasRetryExecutionResult(
        Long rawResult, // Redis Lua 실행의 원시 반환값(1=성공, 0=stale, null/기타는 호출부에서 retryable 실패로 매핑)
        DataAccessException lastFailure // 재시도 소진 후 남은 마지막 Redis 접근 예외(성공 경로에서는 null)
) {
    public static TrafficRedisCasRetryExecutionResult success(Long rawResult) {
        return new TrafficRedisCasRetryExecutionResult(rawResult, null);
    }

    public static TrafficRedisCasRetryExecutionResult failure(DataAccessException lastFailure) {
        return new TrafficRedisCasRetryExecutionResult(null, lastFailure);
    }
}
