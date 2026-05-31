package com.pooli.traffic.service.runtime;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;

/**
 * Redis 접근 실패를 예외 타입 체인 기준으로 분류합니다.
 * 문자열 contains 기반 판별은 사용하지 않습니다.
 */
@Component
@Profile({"local", "api", "traffic"})
public class TrafficRedisFailureClassifier {

    /**
     * 예외가 Redis 연결 실패 또는 timeout 계열의 재시도 가능한 인프라 장애인지 판정합니다.
     *
     * <p>호출부가 wrapper 예외를 그대로 넘겨도 하위 판정 메서드가 cause chain 전체를 순회합니다.
     */
    public boolean isRetryableInfrastructureFailure(Throwable throwable) {
        return isConnectionFailure(throwable) || isTimeoutFailure(throwable);
    }

    /**
     * 예외 cause chain에 Redis 연결 실패로 볼 수 있는 타입이 포함되어 있는지 확인합니다.
     */
    public boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        // Redis 예외는 상위 프레임워크 예외로 여러 번 래핑될 수 있으므로 cause chain 전체를 순회합니다.
        while (current != null) {
            if (current instanceof RedisConnectionFailureException
                    || current instanceof RedisConnectionException
                    || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 예외 cause chain에 Redis 명령 timeout 또는 네트워크 timeout으로 볼 수 있는 타입이 포함되어 있는지 확인합니다.
     */
    public boolean isTimeoutFailure(Throwable throwable) {
        Throwable current = throwable;
        // Timeout 원인이 중첩 예외의 내부 cause에 숨어 있을 수 있어 chain 끝까지 검사합니다.
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || current instanceof RedisCommandTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
