package com.pooli.traffic.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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

import com.pooli.traffic.domain.enums.TrafficPolicyLuaScriptType;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryInvoker;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

@ExtendWith(MockitoExtension.class)
class TrafficPolicyLuaScriptInfraServiceTest {

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private TrafficRedisCasRetryInvoker trafficRedisCasRetryInvoker;

    @InjectMocks
    private TrafficPolicyLuaScriptInfraService trafficPolicyLuaScriptInfraService;

    @BeforeEach
    void setUpScripts() {
        trafficPolicyLuaScriptInfraService.initializeScripts();
    }

    @Nested
    @DisplayName("initializeScripts 테스트")
    class InitializeScriptsTest {

        @Test
        @DisplayName("policy Lua enum 전체를 Long script registry에 등록")
        void registersAllPolicyLuaScripts() {
            @SuppressWarnings("unchecked")
            Map<TrafficPolicyLuaScriptType, RedisScript<Long>> registry =
                    (Map<TrafficPolicyLuaScriptType, RedisScript<Long>>) ReflectionTestUtils.getField(
                            trafficPolicyLuaScriptInfraService,
                            "longScriptRegistry"
                    );

            assertNotNull(registry);
            assertEquals(TrafficPolicyLuaScriptType.values().length, registry.size());
        }
    }

    @Nested
    @DisplayName("executeLongScript 결과 매핑")
    class ExecuteLongScriptTest {

        @Test
        @DisplayName("raw result가 1이면 SUCCESS")
        void mapsRawResultOneToSuccess() {
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(1L));

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.SUCCESS, result);
        }

        @Test
        @DisplayName("raw result가 0이면 STALE_REJECTED")
        void mapsRawResultZeroToStaleRejected() {
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(0L));

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.STALE_REJECTED, result);
        }

        @Test
        @DisplayName("raw result가 null이면 RETRYABLE_FAILURE")
        void mapsNullRawResultToRetryableFailure() {
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(null));

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
        }

        @Test
        @DisplayName("raw result가 예상 범위 밖이면 RETRYABLE_FAILURE")
        void mapsUnexpectedRawResultToRetryableFailure() {
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.success(99L));

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
        }

        @Test
        @DisplayName("retry 결과의 마지막 예외가 connection 계열이면 CONNECTION_FAILURE")
        void mapsRecoveredConnectionFailureToConnectionFailure() {
            DataAccessResourceFailureException connectionFailure =
                    new DataAccessResourceFailureException("redis down");
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.failure(connectionFailure));
            when(trafficRedisFailureClassifier.isConnectionFailure(connectionFailure)).thenReturn(true);

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.CONNECTION_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(connectionFailure);
        }

        @Test
        @DisplayName("retry 결과의 마지막 예외가 비-connection이면 RETRYABLE_FAILURE")
        void mapsRecoveredNonConnectionFailureToRetryableFailure() {
            QueryTimeoutException timeoutFailure = new QueryTimeoutException("redis timeout");
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenReturn(TrafficRedisCasRetryExecutionResult.failure(timeoutFailure));
            when(trafficRedisFailureClassifier.isConnectionFailure(timeoutFailure)).thenReturn(false);

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.RETRYABLE_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(timeoutFailure);
        }

        @Test
        @DisplayName("retry invoker가 DataAccessException을 직접 던져도 기존 분류 계약을 유지")
        void classifiesDataAccessExceptionThrownByRetryInvoker() {
            DataAccessResourceFailureException connectionFailure =
                    new DataAccessResourceFailureException("redis down");
            when(trafficRedisCasRetryInvoker.execute(any(), anyList(), any(Object[].class)))
                    .thenThrow(connectionFailure);
            when(trafficRedisFailureClassifier.isConnectionFailure(connectionFailure)).thenReturn(true);

            PolicySyncResult result = trafficPolicyLuaScriptInfraService.executeLongScript(
                    TrafficPolicyLuaScriptType.POLICY_VALUE_CAS,
                    List.of("policy:key"),
                    "10",
                    "1"
            );

            assertEquals(PolicySyncResult.CONNECTION_FAILURE, result);
            verify(trafficRedisFailureClassifier).isConnectionFailure(connectionFailure);
        }
    }
}
