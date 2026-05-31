package com.pooli.traffic.service.restore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.config.TrafficRestoreProperties;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 장애 복구 전역 flag를 fail-closed 방식으로 조회한다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestorePolicyFlagService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRestoreProperties trafficRestoreProperties;
    private final ObjectProvider<TrafficPolicyBootstrapService> trafficPolicyBootstrapServiceProvider;
    private final PolicyBackOfficeMapper policyBackOfficeMapper;

    /**
     * 복구 flag 활성 여부를 조회하고, 상태가 불명확하면 traffic 차단을 위해 true를 반환한다.
     */
    public boolean isRestoreActiveFailClosed() {
        String policyKey = trafficRedisKeyFactory.policyKey(trafficRestoreProperties.getRestorePolicyId());
        try {
            String value = readPolicyValue(policyKey);
            if (value == null) {
                hydratePolicySnapshot();
                value = readPolicyValue(policyKey);
            }

            if ("0".equals(value)) {
                return false;
            }
            if ("1".equals(value)) {
                return true;
            }

            log.warn("traffic_restore_policy_flag_unknown_value key={} value={}", policyKey, value);
            return true;
        } catch (Exception e) {
            log.warn("traffic_restore_policy_flag_fail_closed key={}", policyKey, e);
            return true;
        }
    }

    /**
     * Redis 장애 복구 시작 시 traffic 진입 차단 flag를 활성화한다.
     */
    public void activateRestoreFlag() {
        int policyId = trafficRestoreProperties.getRestorePolicyId();
        // 1. MySQL의 8번 정책 값을 true로 갱신
        policyBackOfficeMapper.updatePolicyActiveStatus(policyId, true);

        // 2. 다른 정책들과 함께 전체 정책 스냅샷을 Redis에 동기화
        hydratePolicySnapshot();

        // 3. Redis key 직접 갱신 (락 실패 대비 및 명시적 갱신 보장)
        String policyKey = trafficRedisKeyFactory.policyKey(policyId);
        cacheStringRedisTemplate.opsForHash().put(policyKey, "value", "1");
        cacheStringRedisTemplate.opsForHash().put(policyKey, "version", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * Redis 장애 복구 완료 시 traffic 진입 차단 flag를 비활성화한다.
     */
    public void deactivateRestoreFlag() {
        int policyId = trafficRestoreProperties.getRestorePolicyId();
        // 1. MySQL의 8번 정책 값을 false로 갱신
        policyBackOfficeMapper.updatePolicyActiveStatus(policyId, false);

        // 2. Redis key 직접 갱신
        String policyKey = trafficRedisKeyFactory.policyKey(policyId);
        cacheStringRedisTemplate.opsForHash().put(policyKey, "value", "0");
        cacheStringRedisTemplate.opsForHash().put(policyKey, "version", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * Redis policy hash의 value 필드를 읽는다.
     */
    private String readPolicyValue(String policyKey) {
        Object value = cacheStringRedisTemplate.opsForHash().get(policyKey, "value");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * policy key 누락 시 가능한 환경에서 DB snapshot을 Redis에 1회 hydrate한다.
     */
    private void hydratePolicySnapshot() {
        TrafficPolicyBootstrapService bootstrapService = trafficPolicyBootstrapServiceProvider.getIfAvailable();
        if (bootstrapService == null) {
            return;
        }
        bootstrapService.hydrateOnDemand();
    }
}
