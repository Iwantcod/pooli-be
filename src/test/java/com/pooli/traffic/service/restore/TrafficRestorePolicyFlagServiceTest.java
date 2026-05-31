package com.pooli.traffic.service.restore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.config.TrafficRestoreProperties;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePolicyFlagServiceTest {

    private static final String RESTORE_POLICY_KEY = "pooli:policy:8";

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private ObjectProvider<TrafficPolicyBootstrapService> bootstrapServiceProvider;

    @Mock
    private TrafficPolicyBootstrapService bootstrapService;

    @Mock
    private PolicyBackOfficeMapper policyBackOfficeMapper;

    private TrafficRestorePolicyFlagService service;

    @BeforeEach
    void setUp() {
        TrafficRestoreProperties properties = new TrafficRestoreProperties();
        properties.setRestorePolicyId(8);
        service = new TrafficRestorePolicyFlagService(
                cacheStringRedisTemplate,
                trafficRedisKeyFactory,
                properties,
                bootstrapServiceProvider,
                policyBackOfficeMapper
        );
        when(trafficRedisKeyFactory.policyKey(8)).thenReturn(RESTORE_POLICY_KEY);
    }

    @Test
    @DisplayName("복구 flag value가 0이면 traffic을 허용한다")
    void allowsTrafficWhenRedisFlagIsZero() {
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RESTORE_POLICY_KEY, "value")).thenReturn("0");

        assertFalse(service.isRestoreActiveFailClosed());
    }

    @Test
    @DisplayName("복구 flag value가 1이면 traffic을 차단한다")
    void blocksTrafficWhenRedisFlagIsOne() {
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RESTORE_POLICY_KEY, "value")).thenReturn("1");

        assertTrue(service.isRestoreActiveFailClosed());
    }

    @Test
    @DisplayName("복구 flag key가 없으면 policy hydrate를 1회 시도한 뒤 다시 조회한다")
    void hydratesOnceWhenRedisFlagIsMissing() {
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(RESTORE_POLICY_KEY, "value")).thenReturn(null, "0");
        when(bootstrapServiceProvider.getIfAvailable()).thenReturn(bootstrapService);

        assertFalse(service.isRestoreActiveFailClosed());

        verify(bootstrapService).hydrateOnDemand();
    }

    @Test
    @DisplayName("Redis 조회가 실패하면 fail-closed로 traffic을 차단한다")
    void blocksTrafficWhenRedisReadFails() {
        when(cacheStringRedisTemplate.opsForHash()).thenThrow(new IllegalStateException("redis down"));

        assertTrue(service.isRestoreActiveFailClosed());

        verify(bootstrapService, never()).hydrateOnDemand();
    }

    @Test
    @DisplayName("복구 완료 시 traffic 진입 차단 flag를 비활성화한다")
    void deactivatesRestoreFlag() {
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        service.deactivateRestoreFlag();

        verify(policyBackOfficeMapper).updatePolicyActiveStatus(8, false);
        verify(hashOperations).put(RESTORE_POLICY_KEY, "value", "0");
    }

    @Test
    @DisplayName("복구 시작 시 traffic 진입 차단 flag를 활성화한다")
    void activatesRestoreFlag() {
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(bootstrapServiceProvider.getIfAvailable()).thenReturn(bootstrapService);

        service.activateRestoreFlag();

        verify(policyBackOfficeMapper).updatePolicyActiveStatus(8, true);
        verify(bootstrapService).hydrateOnDemand();
        verify(hashOperations).put(RESTORE_POLICY_KEY, "value", "1");
    }
}
