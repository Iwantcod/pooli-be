package com.pooli.traffic.service.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

class TrafficRedisFailureClassifierTest {

    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier = new TrafficRedisFailureClassifier();

    @Test
    @DisplayName("wrapper 예외 내부의 timeout 예외도 retryable 인프라 장애로 판정한다")
    void detectsTimeoutFailureInsideWrapperException() {
        RuntimeException wrapper = new RuntimeException(
                "wrapped redis timeout",
                new QueryTimeoutException("redis timeout")
        );

        assertTrue(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(wrapper));
        assertTrue(trafficRedisFailureClassifier.isTimeoutFailure(wrapper));
        assertFalse(trafficRedisFailureClassifier.isConnectionFailure(wrapper));
    }

    @Test
    @DisplayName("wrapper 예외 내부의 connection 예외도 retryable 인프라 장애로 판정한다")
    void detectsConnectionFailureInsideWrapperException() {
        RuntimeException wrapper = new RuntimeException(
                "wrapped redis connection failure",
                new RedisConnectionFailureException("redis down")
        );

        assertTrue(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(wrapper));
        assertTrue(trafficRedisFailureClassifier.isConnectionFailure(wrapper));
        assertFalse(trafficRedisFailureClassifier.isTimeoutFailure(wrapper));
    }

    @Test
    @DisplayName("분류 대상 타입이 cause chain에 없으면 retryable 인프라 장애가 아니다")
    void returnsFalseWhenCauseChainDoesNotContainRetryableInfrastructureFailure() {
        RuntimeException wrapper = new RuntimeException(
                "wrapped business failure",
                new IllegalArgumentException("not redis infrastructure")
        );

        assertFalse(trafficRedisFailureClassifier.isRetryableInfrastructureFailure(wrapper));
        assertFalse(trafficRedisFailureClassifier.isConnectionFailure(wrapper));
        assertFalse(trafficRedisFailureClassifier.isTimeoutFailure(wrapper));
    }
}
