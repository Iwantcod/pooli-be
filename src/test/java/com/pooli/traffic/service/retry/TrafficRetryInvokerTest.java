package com.pooli.traffic.service.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.pooli.common.config.TrafficRetryConfig;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;

@DisplayName("Retry invoker @Retryable/@Recover 회귀 테스트")
class TrafficRetryInvokerTest {

    // 테스트 지연을 없애고 시도 횟수만 검증하기 위해 backoff를 0으로 고정합니다.
    private final ApplicationContextRunner redisCasContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TrafficRetryConfig.class, TrafficRedisCasRetryInvoker.class)
            .withPropertyValues(
                    "app.traffic.deduct.redis-retry.max-attempts=3",
                    "app.traffic.deduct.redis-retry.backoff-ms=0"
            )
            .withBean("cacheStringRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    private final ApplicationContextRunner dedupeDeleteContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TrafficRetryConfig.class,
                    TrafficInFlightDedupeDeleteRetryInvoker.class,
                    DedupeDeleteRetryTestConfig.class
            )
            .withPropertyValues(
                    "app.traffic.deduct.redis-retry.max-attempts=3",
                    "app.traffic.deduct.redis-retry.backoff-ms=0"
            );

    @Nested
    @DisplayName("TrafficRedisCasRetryInvoker")
    class RedisCasRetryInvokerTest {

        @Test
        @DisplayName("retryable DataAccessException이 발생해도 재시도 중 복구되면 성공 결과를 반환한다")
        void returnsSuccessWhenRecoveredDuringRetry() {
            redisCasContextRunner.run(context -> {
                StringRedisTemplate redisTemplate = context.getBean("cacheStringRedisTemplate", StringRedisTemplate.class);
                TrafficRedisCasRetryInvoker invoker = context.getBean(TrafficRedisCasRetryInvoker.class);
                RedisScript<Long> script = mock(RedisScript.class);
                AtomicInteger attempts = new AtomicInteger(0);
                DataAccessResourceFailureException firstFailure =
                        new DataAccessResourceFailureException("redis down-1");
                DataAccessResourceFailureException secondFailure =
                        new DataAccessResourceFailureException("redis down-2");

                when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw firstFailure;
                    }
                    if (attempt == 2) {
                        throw secondFailure;
                    }
                    return 1L;
                });

                TrafficRedisCasRetryExecutionResult result =
                        invoker.execute(script, List.of("policy:key"), new Object[]{"101", "payload"});

                assertEquals(3, attempts.get());
                assertEquals(1L, result.rawResult());
                assertNull(result.lastFailure());
                verify(redisTemplate, times(3)).execute(any(), anyList(), any(Object[].class));
            });
        }

        @Test
        @DisplayName("retryable DataAccessException이 계속되면 재시도 소진 후 recover 결과를 반환한다")
        void returnsFailureWhenRetryExhausted() {
            redisCasContextRunner.run(context -> {
                StringRedisTemplate redisTemplate = context.getBean("cacheStringRedisTemplate", StringRedisTemplate.class);
                TrafficRedisCasRetryInvoker invoker = context.getBean(TrafficRedisCasRetryInvoker.class);
                RedisScript<Long> script = mock(RedisScript.class);
                AtomicInteger attempts = new AtomicInteger(0);
                DataAccessResourceFailureException failure =
                        new DataAccessResourceFailureException("redis timeout");

                when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
                    attempts.incrementAndGet();
                    throw failure;
                });

                TrafficRedisCasRetryExecutionResult result =
                        invoker.execute(script, List.of("policy:key"), new Object[]{"102", "payload"});

                assertEquals(4, attempts.get());
                assertNull(result.rawResult());
                assertSame(failure, result.lastFailure());
                verify(redisTemplate, times(4)).execute(any(), anyList(), any(Object[].class));
            });
        }
    }

    @Nested
    @DisplayName("TrafficInFlightDedupeDeleteRetryInvoker")
    class DedupeDeleteRetryInvokerTest {

        @Test
        @DisplayName("런타임 예외가 일부 발생해도 재시도 중 복구되면 success DTO를 반환한다")
        void returnsSuccessWhenRecoveredDuringRetry() {
            dedupeDeleteContextRunner.run(context -> {
                TrafficInFlightDedupeService dedupeService = context.getBean(TrafficInFlightDedupeService.class);
                TrafficInFlightDedupeDeleteRetryInvoker invoker =
                        context.getBean(TrafficInFlightDedupeDeleteRetryInvoker.class);
                AtomicInteger attempts = new AtomicInteger(0);
                RuntimeException firstFailure = new RuntimeException("delete-fail-1");
                RuntimeException secondFailure = new RuntimeException("delete-fail-2");

                doAnswer(invocation -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw firstFailure;
                    }
                    if (attempt == 2) {
                        throw secondFailure;
                    }
                    return null;
                }).when(dedupeService).delete("trace-recover");

                TrafficInFlightDedupeDeleteRetryExecutionResult result = invoker.delete("trace-recover");

                assertTrue(result.succeeded());
                assertEquals(3, result.attemptCount());
                assertEquals(2, result.failedAttemptCount());
                assertSame(secondFailure, result.lastFailure());
                verify(dedupeService, times(3)).delete("trace-recover");
            });
        }

        @Test
        @DisplayName("런타임 예외가 지속되면 재시도 소진 후 failure DTO를 반환한다")
        void returnsFailureWhenRetryExhausted() {
            dedupeDeleteContextRunner.run(context -> {
                TrafficInFlightDedupeService dedupeService = context.getBean(TrafficInFlightDedupeService.class);
                TrafficInFlightDedupeDeleteRetryInvoker invoker =
                        context.getBean(TrafficInFlightDedupeDeleteRetryInvoker.class);
                AtomicInteger attempts = new AtomicInteger(0);
                RuntimeException failure = new RuntimeException("delete-timeout");

                doAnswer(invocation -> {
                    attempts.incrementAndGet();
                    throw failure;
                }).when(dedupeService).delete("trace-exhausted");

                TrafficInFlightDedupeDeleteRetryExecutionResult result = invoker.delete("trace-exhausted");

                assertFalse(result.succeeded());
                assertEquals(4, attempts.get());
                assertEquals(4, result.attemptCount());
                assertEquals(4, result.failedAttemptCount());
                assertSame(failure, result.lastFailure());
                verify(dedupeService, times(4)).delete("trace-exhausted");
            });
        }
    }

    @TestConfiguration
    static class DedupeDeleteRetryTestConfig {

        @Bean
        TrafficInFlightDedupeService trafficInFlightDedupeService() {
            return mock(TrafficInFlightDedupeService.class);
        }
    }
}
