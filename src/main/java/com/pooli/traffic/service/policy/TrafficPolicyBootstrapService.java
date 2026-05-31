package com.pooli.traffic.service.policy;

import static java.util.Map.entry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.policy.domain.dto.response.PolicyActivationSnapshotResDto;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * POLICY 전역 활성화 상태를 Redis policy:{policyId} 키에 동기화하는 bootstrap/reconciliation 서비스입니다.
 *
 * <p>동작 규칙:
 * 1) 부팅 시 POLICY 스냅샷을 읽어 필수 정책 ID(1~7) 존재를 검증(fail-fast)
 * 2) 분산락(NX PX) 획득 인스턴스만 pipeline으로 Redis 반영
 * 3) 주기적 reconciliation으로 DB->Redis 불일치를 보정
 * 4) lock 해제는 소유자 비교 Lua로 수행
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficPolicyBootstrapService {

    private static final long POLICY_BOOTSTRAP_LOCK_TTL_MS = 30_000L;

    private static final Map<Integer, String> REQUIRED_POLICY_MAPPING = Map.ofEntries(
            entry(1, "REPEAT_BLOCK_POLICY"),
            entry(2, "IMMEDIATELY_BLOCK_POLICY"),
            entry(3, "LINE_LIMIT_SHARED_POLICY"),
            entry(4, "LINE_LIMIT_DAILY_POLICY"),
            entry(5, "APP_POLICY_DATA_POLICY"),
            entry(6, "APP_POLICY_SPEED_POLICY"),
            entry(7, "APP_POLICY_WHITELIST_POLICY")
    );

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @PostConstruct
    /**
      * 애플리케이션 부팅 시 정책 활성화 키 bootstrap을 1회 수행합니다.
     */
    public void bootstrapOnStartup() {
        synchronizePolicyActivationSnapshot("startup", true);
    }

//    @Scheduled(
//            fixedDelayString = "${app.policy.bootstrap.reconcile-interval-ms:300000}",
//            initialDelayString = "${app.policy.bootstrap.reconcile-initial-delay-ms:60000}"
//    )
    /**
      * 주기적으로 정책 활성화 키를 재동기화해 DB/Redis 불일치를 보정합니다.
     */
    @Deprecated
    public void reconcilePolicyActivationSnapshot() {
        try {
            synchronizePolicyActivationSnapshot("reconcile", false);
        } catch (Exception e) {
            // reconciliation 실패가 런타임 스레드를 죽이지 않도록 로그만 남기고 다음 주기를 기다린다.
            log.error("traffic_policy_bootstrap_reconcile_failed", e);
        }
    }

    /**
     * 워커가 전역 정책 키 누락을 감지했을 때 전체 정책 스냅샷 hydrate를 트리거합니다.
     * 기존 bootstrap/reconciliation과 동일한 lock 키, TTL, 획득 규칙을 그대로 재사용합니다.
     */
    public void hydrateOnDemand() {
        synchronizePolicyActivationSnapshot("on_demand", false);
    }

    /**
     * 차감 preflight에서 전역 policy key 누락을 감지했을 때 lock-first 방식으로 snapshot hydrate를 시도합니다.
     *
     * <p>정상 hot-path에서는 Redis key 존재 확인만 수행하고, 누락 시에만 lock을 획득합니다.
     * lock 획득 후에도 key를 다시 확인해 다른 worker가 이미 hydrate한 경우 DB 조회와 pipeline 반영을 생략합니다.
     *
     * @param policyKeys preflight에서 확인한 전역 policy Redis key 목록
     */
    public void hydrateOnDemandIfAnyPolicyKeyMissing(Collection<String> policyKeys) {
        // (1) hot-path quick check: 모든 정책 키가 이미 Redis에 존재하는지 빠르게 확인합니다. (allPolicyKeysExist)
        if (allPolicyKeysExist(policyKeys)) {
            return;
        }

        // (2) lockKey와 lockOwner를 생성하고 tryAcquireLock을 통해 분산 락 획득을 시도합니다.
        String lockKey = trafficRedisKeyFactory.policyBootstrapLockKey();
        String lockOwner = buildLockOwner("on_demand_preflight");
        boolean lockAcquired = tryAcquireLock(lockKey, lockOwner);
        if (!lockAcquired) {
            log.info("traffic_policy_bootstrap_preflight_lock_skipped lockKey={}", lockKey);
            return;
        }

        try {
            // (3) 락 획득에 성공한 후, 다른 인스턴스가 그 사이에 적재를 완료했는지 allPolicyKeysExist로 재검사합니다.
            if (allPolicyKeysExist(policyKeys)) {
                return;
            }
            // (4) 여전히 키가 누락되어 있다면 synchronizePolicyActivationSnapshotWithoutLock을 통해 스냅샷을 적재합니다.
            synchronizePolicyActivationSnapshotWithoutLock("on_demand_preflight", false);
        } finally {
            // (5) 작업 성공 여부와 상관없이 releaseLock을 통해 락을 안전하게 해제합니다.
            releaseLock(lockKey, lockOwner);
        }
    }

    /**
     * POLICY 스냅샷을 Redis에 동기화하는 공통 진입점입니다.
     *
     * @param executionType startup/reconcile 구분 로그
     * @param failFastOnMissingRequiredIds 필수 policy id 누락 시 예외 전파 여부
     */
    private void synchronizePolicyActivationSnapshot(String executionType, boolean failFastOnMissingRequiredIds) {
        List<PolicyActivationSnapshotResDto> snapshots = policyBackOfficeMapper.selectPolicyActivationSnapshot();
        if (!validateRequiredPolicyIds(snapshots, failFastOnMissingRequiredIds)) {
            return;
        }

        String lockKey = trafficRedisKeyFactory.policyBootstrapLockKey();
        String lockOwner = buildLockOwner(executionType);
        boolean lockAcquired = tryAcquireLock(lockKey, lockOwner);
        if (!lockAcquired) {
            log.info(
                    "traffic_policy_bootstrap_lock_skipped executionType={} lockKey={}",
                    executionType,
                    lockKey
            );
            return;
        }

        try {
            syncSnapshotToRedis(snapshots);
            log.info(
                    "traffic_policy_bootstrap_completed executionType={} policyCount={}",
                    executionType,
                    snapshots.size()
            );
        } finally {
            releaseLock(lockKey, lockOwner);
        }
    }

    /**
     * 이미 policy bootstrap lock을 보유한 호출자가 DB snapshot을 Redis에 반영합니다.
     */
    private void synchronizePolicyActivationSnapshotWithoutLock(
            String executionType,
            boolean failFastOnMissingRequiredIds
    ) {
        List<PolicyActivationSnapshotResDto> snapshots = policyBackOfficeMapper.selectPolicyActivationSnapshot();
        if (!validateRequiredPolicyIds(snapshots, failFastOnMissingRequiredIds)) {
            return;
        }

        syncSnapshotToRedis(snapshots);
        log.info(
                "traffic_policy_bootstrap_completed executionType={} policyCount={}",
                executionType,
                snapshots.size()
        );
    }

    /**
     * preflight 대상 policy key가 모두 Redis에 존재하는지 확인합니다.
     */
    private boolean allPolicyKeysExist(Collection<String> policyKeys) {
        if (policyKeys == null || policyKeys.isEmpty()) {
            return false;
        }
        for (String policyKey : policyKeys) {
            if (policyKey == null || policyKey.isBlank()) {
                return false;
            }
            if (!Boolean.TRUE.equals(cacheStringRedisTemplate.hasKey(policyKey))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 필수 정책 ID(1~7)가 DB 스냅샷에 모두 존재하는지 검증합니다.
     */
    private boolean validateRequiredPolicyIds(
            List<PolicyActivationSnapshotResDto> snapshots,
            boolean failFastOnMissingRequiredIds
    ) {
        Set<Integer> existingPolicyIds = snapshots == null
                ? Set.of()
                : snapshots.stream()
                        .map(PolicyActivationSnapshotResDto::getPolicyId)
                        .filter(id -> id != null && id > 0)
                        .collect(Collectors.toSet());

        Set<Integer> missingPolicyIds = new HashSet<>(REQUIRED_POLICY_MAPPING.keySet());
        missingPolicyIds.removeAll(existingPolicyIds);
        if (missingPolicyIds.isEmpty()) {
            return true;
        }

        String missingPolicyDescription = missingPolicyIds.stream()
                .sorted()
                .map(policyId -> policyId + ":" + REQUIRED_POLICY_MAPPING.get(policyId))
                .collect(Collectors.joining(", "));
        String message = "필수 POLICY ID가 누락되어 Redis bootstrap을 진행할 수 없습니다. missing=[" + missingPolicyDescription + "]";
        if (failFastOnMissingRequiredIds) {
            throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, message);
        }
        log.error("traffic_policy_bootstrap_validation_failed message={}", message);
        return false;
    }

    /**
     * DB 스냅샷을 Redis policy 키에 pipeline으로 일괄 반영합니다.
     */
    private void syncSnapshotToRedis(List<PolicyActivationSnapshotResDto> snapshots) {
        long bootstrapVersionEpochMillis = resolveBootstrapVersionEpochMillis(snapshots);
        String versionKey = trafficRedisKeyFactory.policyBootstrapVersionKey();

        cacheStringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                RedisOperations<String, String> stringOperations = (RedisOperations<String, String>) operations;
                ValueOperations<String, String> valueOperations = stringOperations.opsForValue();

                for (PolicyActivationSnapshotResDto snapshot : snapshots) {
                    if (snapshot == null || snapshot.getPolicyId() == null) {
                        continue;
                    }
                    long policyId = snapshot.getPolicyId();
                    String policyKey = trafficRedisKeyFactory.policyKey(policyId);
                    String policyValue = Boolean.TRUE.equals(snapshot.getIsActive()) ? "1" : "0";
                    stringOperations.opsForHash().put(policyKey, "value", policyValue);
                    stringOperations.opsForHash().put(policyKey, "version", String.valueOf(bootstrapVersionEpochMillis));
                }

                valueOperations.set(versionKey, String.valueOf(bootstrapVersionEpochMillis));
                return null;
            }
        });
    }

    /**
     * 스냅샷의 최신 변경 시각을 epoch seconds로 계산합니다.
     * updatedAt이 없으면 createdAt을 사용하고, 둘 다 없으면 현재 시각을 사용합니다.
     */
    private long resolveBootstrapVersionEpochMillis(List<PolicyActivationSnapshotResDto> snapshots) {
        ZoneId zoneId = trafficRedisRuntimePolicy.zoneId();
        return snapshots.stream()
                .map(this::resolveLatestTimestamp)
                .filter(timestamp -> timestamp != null)
                .mapToLong(timestamp -> timestamp.atZone(zoneId).toInstant().toEpochMilli())
                .max()
                .orElseGet(() -> Instant.now().toEpochMilli());
    }

    /**
     * 스냅샷에서 최신 시각(updatedAt 우선, 없으면 createdAt)을 반환합니다.
     */
    private LocalDateTime resolveLatestTimestamp(PolicyActivationSnapshotResDto snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (snapshot.getUpdatedAt() != null) {
            return snapshot.getUpdatedAt();
        }
        return snapshot.getCreatedAt();
    }

    /**
     * 분산락 획득을 시도합니다.
     */
    private boolean tryAcquireLock(String lockKey, String lockOwner) {
        Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockOwner,
                Duration.ofMillis(POLICY_BOOTSTRAP_LOCK_TTL_MS)
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * lock 소유자 토큰을 생성합니다.
     */
    private String buildLockOwner(String executionType) {
        return executionType + ":" + UUID.randomUUID();
    }

    /**
     * lock 소유자 비교 후 안전하게 lock을 해제합니다.
     */
    private void releaseLock(String lockKey, String lockOwner) {
        try {
            trafficLuaScriptInfraService.executeLockRelease(lockKey, lockOwner);
        } catch (Exception e) {
            log.warn("traffic_policy_bootstrap_lock_release_failed lockKey={}", lockKey, e);
        }
    }
}
