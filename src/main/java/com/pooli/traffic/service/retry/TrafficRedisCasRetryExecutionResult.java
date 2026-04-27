package com.pooli.traffic.service.retry;

import org.springframework.dao.DataAccessException;

/**
 * Redis CAS retry 실행 결과를 호출부로 전달하는 불변 DTO입니다.
 */
public record TrafficRedisCasRetryExecutionResult(
        Long rawResult,
        DataAccessException lastFailure
) {
    public static TrafficRedisCasRetryExecutionResult success(Long rawResult) {
        return new TrafficRedisCasRetryExecutionResult(rawResult, null);
    }

    public static TrafficRedisCasRetryExecutionResult failure(DataAccessException lastFailure) {
        return new TrafficRedisCasRetryExecutionResult(null, lastFailure);
    }
}
