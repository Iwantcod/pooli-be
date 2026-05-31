package com.pooli.traffic.service.retry;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * policy CAS Lua 실행의 Redis 접근부를 전담하는 retry 어댑터입니다.
 * executeCas 호출부는 이 결과를 PolicySyncResult로만 매핑하고, 예외 재시도는 본 클래스에서 처리합니다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRedisCasRetryInvoker {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * Redis CAS Lua 실행을 retryable DataAccessException 기준으로 즉시 재시도합니다.
     * - 최초 1회 + 최대 재시도 3회 (총 4회)
     * - backoff는 50/100/200ms 지수 규칙을 사용합니다.
     */
    @Retryable(
            retryFor = DataAccessException.class,
            maxAttemptsExpression = "#{${app.traffic.deduct.redis-retry.max-attempts:3} + 1}",
            backoff = @Backoff(
                    delayExpression = "${app.traffic.deduct.redis-retry.backoff-ms:50}",
                    multiplier = 2.0
            )
    )
    public TrafficRedisCasRetryExecutionResult execute(
            RedisScript<Long> script,
            List<String> keys,
            Object[] args
    ) {
        Object[] normalizedArgs = args == null ? new Object[0] : args;
        Long rawResult = cacheStringRedisTemplate.execute(script, keys, normalizedArgs);
        return TrafficRedisCasRetryExecutionResult.success(rawResult);
    }

    /**
     * retry 소진 시 마지막 DataAccessException을 포함한 실패 결과를 반환합니다.
     * executeCas 호출부는 이 값을 기준으로 CONNECTION/RETRYABLE 경계를 유지합니다.
     */
    @Recover
    public TrafficRedisCasRetryExecutionResult recover(
            DataAccessException exception,
            RedisScript<Long> script,
            List<String> keys,
            Object[] args
    ) {
        return TrafficRedisCasRetryExecutionResult.failure(exception);
    }
}
