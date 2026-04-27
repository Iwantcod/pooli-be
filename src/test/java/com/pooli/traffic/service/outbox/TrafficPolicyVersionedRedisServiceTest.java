package com.pooli.traffic.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryInvoker;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

@ExtendWith(MockitoExtension.class)
class TrafficPolicyVersionedRedisServiceTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private TrafficRedisCasRetryInvoker trafficRedisCasRetryInvoker;

    @Mock
    private RedisScript<Long> valueCasScript;

    @InjectMocks
    private TrafficPolicyVersionedRedisService trafficPolicyVersionedRedisService;

    @BeforeEach
    void setUpScripts() {
        ReflectionTestUtils.setField(trafficPolicyVersionedRedisService, "valueCasScript", valueCasScript);
    }

    @Nested
    @DisplayName("executeCas 분류/매핑 계약")
    class ExecuteCasContractTest {

        @Test
        @DisplayName("retry 결과의 마지막 예외가 connection 계열이면 CONNECTION_FAILURE")
        void returnsConnectionFailureWhenRecoveredFailureIsConnectionRelated() {
            DataAccessResourceFailureException connectionFailure = new DataAccessResourceFailureException("redis down");
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.failure(connectionFailure));
            when(trafficRedisFailureClassifier.isConnectionFailure(connectionFailure)).thenReturn(true);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 10L);

            assertEquals(PolicySyncResult.CONNECTION_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(connectionFailure);
        }

        @Test
        @DisplayName("retry 결과의 마지막 예외가 비-connection이면 RETRYABLE_FAILURE")
        void returnsRetryableFailureWhenRecoveredFailureIsNonConnection() {
            QueryTimeoutException timeoutFailure = new QueryTimeoutException("redis timeout");
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.failure(timeoutFailure));
            when(trafficRedisFailureClassifier.isConnectionFailure(timeoutFailure)).thenReturn(false);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 11L);

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(timeoutFailure);
        }

        @Test
        @DisplayName("raw result가 1이면 SUCCESS")
        void mapsRawResultOneToSuccess() {
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(1L));

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 12L);

            assertEquals(PolicySyncResult.SUCCESS, result);
        }

        @Test
        @DisplayName("raw result가 0이면 STALE_REJECTED")
        void mapsRawResultZeroToStaleRejected() {
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(0L));

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 13L);

            assertEquals(PolicySyncResult.STALE_REJECTED, result);
        }

        @Test
        @DisplayName("raw result가 null이면 RETRYABLE_FAILURE")
        void mapsNullRawResultToRetryableFailure() {
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(null));

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 14L);

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
        }

        @Test
        @DisplayName("raw result가 예상 범위 밖이면 RETRYABLE_FAILURE")
        void mapsUnexpectedRawResultToRetryableFailure() {
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(99L));

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 15L);

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
        }

        @Test
        @DisplayName("retry invoker가 DataAccessException을 직접 던져도 기존 분류 계약을 유지")
        void classifiesDataAccessExceptionThrownByRetryInvoker() {
            DataAccessResourceFailureException connectionFailure = new DataAccessResourceFailureException("redis down");
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenThrow(connectionFailure);
            when(trafficRedisFailureClassifier.isConnectionFailure(connectionFailure)).thenReturn(true);

            PolicySyncResult result = trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 16L);

            assertEquals(PolicySyncResult.CONNECTION_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(connectionFailure);
        }

        @Test
        @DisplayName("예상하지 못한 일반 예외는 기존처럼 호출자에게 재전파")
        void rethrowsUnexpectedRuntimeException() {
            IllegalStateException unexpected = new IllegalStateException("unexpected");
            when(trafficRedisCasRetryInvoker.execute(eq(valueCasScript), anyList(), any(Object[].class)))
                    .thenThrow(unexpected);

            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> trafficPolicyVersionedRedisService.syncVersionedValue("policy:key", "payload", 17L)
            );

            assertEquals("unexpected", thrown.getMessage());
        }
    }
}
