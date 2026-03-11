package com.pooli.traffic.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정책 변경 시 Redis 정책 키를 즉시 동기화(write-through)하는 서비스입니다.
 * 트랜잭션이 존재하면 커밋 후 반영하고, 실패 시 재시도 후 예외를 발생시켜 정합성을 지킵니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficPolicyWriteThroughService {

    private static final int WRITE_THROUGH_RETRY_MAX = 3;
    private static final long WRITE_THROUGH_RETRY_BACKOFF_MS = 50L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    /**
     * 정책 활성화 상태를 Redis policy 키에 동기화합니다.
     * - 활성화(true): key 존재(value=1)
     * - 비활성(false): key 삭제
     */
    public void syncPolicyActivation(long policyId, boolean isActive) {
        executeAfterCommit(
                "policy_activation_sync policyId=" + policyId + " isActive=" + isActive,
                () -> {
                    String key = trafficRedisKeyFactory.policyKey(policyId);
                    if (isActive) {
                        cacheStringRedisTemplate.opsForValue().set(key, "1");
                    } else {
                        cacheStringRedisTemplate.delete(key);
                    }
                }
        );
    }

    /**
     * line 단위 한도 정책(daily/shared)을 Redis에 즉시 반영합니다.
     * 정책이 비활성인 경우에는 명세상 무제한(-1)로 저장해 Lua가 제한을 적용하지 않도록 맞춥니다.
     */
    public void syncLineLimit(
            long lineId,
            Long dailyLimit,
            Boolean isDailyActive,
            Long sharedLimit,
            Boolean isSharedActive
    ) {
        executeAfterCommit(
                "line_limit_sync lineId=" + lineId,
                () -> {
                    String dailyLimitKey = trafficRedisKeyFactory.dailyTotalLimitKey(lineId);
                    String monthlySharedLimitKey = trafficRedisKeyFactory.monthlySharedLimitKey(lineId);

                    long dailyLimitValue = resolveLimitValue(dailyLimit, isDailyActive);
                    long sharedLimitValue = resolveLimitValue(sharedLimit, isSharedActive);

                    cacheStringRedisTemplate.opsForValue().set(dailyLimitKey, String.valueOf(dailyLimitValue));
                    cacheStringRedisTemplate.opsForValue().set(monthlySharedLimitKey, String.valueOf(sharedLimitValue));
                }
        );
    }

    /**
     * 즉시 차단 종료 시각을 Redis에 동기화합니다.
     * - null: 차단 해제 상태이므로 키 삭제
     * - 값 존재: Asia/Seoul 기준 epoch second 문자열로 저장
     */
    public void syncImmediateBlockEnd(long lineId, LocalDateTime blockEndAt) {
        executeAfterCommit(
                "immediate_block_sync lineId=" + lineId,
                () -> {
                    String key = trafficRedisKeyFactory.immediatelyBlockEndKey(lineId);
                    if (blockEndAt == null) {
                        cacheStringRedisTemplate.delete(key);
                        return;
                    }

                    long epochSecond = blockEndAt.atZone(trafficRedisRuntimePolicy.zoneId()).toEpochSecond();
                    cacheStringRedisTemplate.opsForValue().set(key, String.valueOf(epochSecond));
                }
        );
    }

    /**
     * 반복 차단 정책 목록을 repeat_block hash에 스냅샷 형태로 동기화합니다.
     * 기존 hash를 먼저 비운 뒤 활성 정책만 다시 적재해 soft-delete/비활성 변경을 즉시 반영합니다.
     */
    public void syncRepeatBlock(long lineId, List<RepeatBlockPolicyResDto> repeatBlocks) {
        executeAfterCommit(
                "repeat_block_sync lineId=" + lineId,
                () -> {
                    String repeatBlockKey = trafficRedisKeyFactory.repeatBlockKey(lineId);

                    // 기존 hash를 먼저 비워 soft-delete/비활성화 변경이 즉시 반영되도록 한다.
                    cacheStringRedisTemplate.delete(repeatBlockKey);

                    Map<String, String> hashToWrite = buildRepeatBlockHash(repeatBlocks);
                    if (!hashToWrite.isEmpty()) {
                        HashOperations<String, String, String> hashOps = cacheStringRedisTemplate.opsForHash();
                        hashOps.putAll(repeatBlockKey, hashToWrite);
                    }
                }
        );
    }

    /**
     * 앱 정책(일 제한/속도 제한/화이트리스트)을 Redis 정책 키에 동기화합니다.
     * isActive=false면 해당 앱의 정책 흔적을 모두 제거하고,
     * isActive=true면 limit/speed/hash 및 whitelist set을 최신값으로 맞춥니다.
     */
    public void syncAppPolicy(
            long lineId,
            int appId,
            boolean isActive,
            Long dataLimit,
            Integer speedLimit,
            boolean isWhitelist
    ) {
        executeAfterCommit(
                "app_policy_sync lineId=" + lineId + " appId=" + appId + " isActive=" + isActive,
                () -> {
                    String appDataDailyLimitKey = trafficRedisKeyFactory.appDataDailyLimitKey(lineId);
                    String appSpeedLimitKey = trafficRedisKeyFactory.appSpeedLimitKey(lineId);
                    String appWhitelistKey = trafficRedisKeyFactory.appWhitelistKey(lineId);

                    String dataField = appDataLimitField(appId);
                    String speedField = appSpeedLimitField(appId);
                    String appMember = String.valueOf(appId);

                    if (!isActive) {
                        // 비활성 정책은 즉시 제거해 차감 Lua가 제한을 보지 않도록 한다.
                        cacheStringRedisTemplate.opsForHash().delete(appDataDailyLimitKey, dataField);
                        cacheStringRedisTemplate.opsForHash().delete(appSpeedLimitKey, speedField);
                        cacheStringRedisTemplate.opsForSet().remove(appWhitelistKey, appMember);
                        return;
                    }

                    long normalizedDataLimit = dataLimit == null ? -1L : dataLimit;
                    int normalizedSpeedLimit = speedLimit == null ? -1 : speedLimit;

                    cacheStringRedisTemplate.opsForHash().put(
                            appDataDailyLimitKey,
                            dataField,
                            String.valueOf(normalizedDataLimit)
                    );
                    cacheStringRedisTemplate.opsForHash().put(
                            appSpeedLimitKey,
                            speedField,
                            String.valueOf(normalizedSpeedLimit)
                    );

                    SetOperations<String, String> setOps = cacheStringRedisTemplate.opsForSet();
                    if (isWhitelist) {
                        setOps.add(appWhitelistKey, appMember);
                    } else {
                        setOps.remove(appWhitelistKey, appMember);
                    }
                }
        );
    }

    /**
     * 앱 정책 삭제 시 Redis의 관련 키 조각(limit/speed/whitelist)을 강제로 제거합니다.
     * soft-delete 이후 stale 정책이 남지 않게 하는 정리용 메서드입니다.
     */
    public void evictAppPolicy(long lineId, int appId) {
        executeAfterCommit(
                "app_policy_evict lineId=" + lineId + " appId=" + appId,
                () -> {
                    String appDataDailyLimitKey = trafficRedisKeyFactory.appDataDailyLimitKey(lineId);
                    String appSpeedLimitKey = trafficRedisKeyFactory.appSpeedLimitKey(lineId);
                    String appWhitelistKey = trafficRedisKeyFactory.appWhitelistKey(lineId);
                    String appMember = String.valueOf(appId);

                    cacheStringRedisTemplate.opsForHash().delete(appDataDailyLimitKey, appDataLimitField(appId));
                    cacheStringRedisTemplate.opsForHash().delete(appSpeedLimitKey, appSpeedLimitField(appId));
                    cacheStringRedisTemplate.opsForSet().remove(appWhitelistKey, appMember);
                }
        );
    }

    /**
     * 앱 정책 스냅샷을 Redis에 일괄 반영합니다.
     * line 단위 app 정책 키 3종을 먼저 비운 뒤, 활성 정책만 다시 적재합니다.
     */
    public void syncAppPolicySnapshot(long lineId, List<AppPolicy> appPolicies) {
        executeAfterCommit(
                "app_policy_snapshot_sync lineId=" + lineId,
                () -> {
                    String appDataDailyLimitKey = trafficRedisKeyFactory.appDataDailyLimitKey(lineId);
                    String appSpeedLimitKey = trafficRedisKeyFactory.appSpeedLimitKey(lineId);
                    String appWhitelistKey = trafficRedisKeyFactory.appWhitelistKey(lineId);

                    cacheStringRedisTemplate.delete(List.of(
                            appDataDailyLimitKey,
                            appSpeedLimitKey,
                            appWhitelistKey
                    ));

                    if (appPolicies == null || appPolicies.isEmpty()) {
                        return;
                    }

                    Map<String, String> dataLimitHash = new HashMap<>();
                    Map<String, String> speedLimitHash = new HashMap<>();
                    Set<String> whitelistMembers = new HashSet<>();

                    for (AppPolicy appPolicy : appPolicies) {
                        if (appPolicy == null || appPolicy.getApplicationId() == null) {
                            continue;
                        }
                        if (!Boolean.TRUE.equals(appPolicy.getIsActive())) {
                            continue;
                        }

                        int appId = appPolicy.getApplicationId();
                        long normalizedDataLimit = appPolicy.getDataLimit() == null ? -1L : appPolicy.getDataLimit();
                        int normalizedSpeedLimit = appPolicy.getSpeedLimit() == null ? -1 : appPolicy.getSpeedLimit();

                        dataLimitHash.put(appDataLimitField(appId), String.valueOf(normalizedDataLimit));
                        speedLimitHash.put(appSpeedLimitField(appId), String.valueOf(normalizedSpeedLimit));

                        if (Boolean.TRUE.equals(appPolicy.getIsWhitelist())) {
                            whitelistMembers.add(String.valueOf(appId));
                        }
                    }

                    HashOperations<String, String, String> hashOps = cacheStringRedisTemplate.opsForHash();
                    if (!dataLimitHash.isEmpty()) {
                        hashOps.putAll(appDataDailyLimitKey, dataLimitHash);
                    }
                    if (!speedLimitHash.isEmpty()) {
                        hashOps.putAll(appSpeedLimitKey, speedLimitHash);
                    }
                    if (!whitelistMembers.isEmpty()) {
                        cacheStringRedisTemplate.opsForSet().add(
                                appWhitelistKey,
                                whitelistMembers.toArray(new String[0])
                        );
                    }
                }
        );
    }

    /**
     * write-through 실행 타이밍을 제어합니다.
     * - 트랜잭션 중: afterCommit 콜백으로 등록(커밋 성공 후 실행)
     * - 트랜잭션 없음: 즉시 실행
     *
     * 이렇게 분리해야 DB 롤백 시 Redis만 먼저 반영되는 정합성 깨짐을 막을 수 있습니다.
     */
    private void executeAfterCommit(String operationName, Runnable redisWriteOperation) {
        Runnable wrappedOperation = () -> executeWithRetry(operationName, redisWriteOperation);

        // 트랜잭션 내에서는 DB 커밋 성공 이후에만 Redis 반영을 수행한다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                /**
                  * `afterCommit` 처리 목적에 맞는 핵심 로직을 수행합니다.
                 */
                public void afterCommit() {
                    wrappedOperation.run();
                }
            });
            return;
        }

        wrappedOperation.run();
    }

    /**
     * Redis write-through를 재시도 정책과 함께 실행합니다.
     * 최대 시도 횟수 초과 시 EXTERNAL_SYSTEM_ERROR로 전환해 상위 트랜잭션이 실패를 인지하도록 합니다.
     */
    private void executeWithRetry(String operationName, Runnable redisWriteOperation) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= WRITE_THROUGH_RETRY_MAX; attempt++) {
            try {
                redisWriteOperation.run();
                return;
            } catch (RuntimeException e) {
                lastException = e;
                if (attempt >= WRITE_THROUGH_RETRY_MAX) {
                    log.error(
                            "traffic_policy_write_through_failed operation={} attempts={}",
                            operationName,
                            attempt,
                            e
                    );
                    throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "정책 Redis 즉시 갱신에 실패했습니다.");
                }

                log.warn(
                        "traffic_policy_write_through_retry operation={} attempt={}/{}",
                        operationName,
                        attempt,
                        WRITE_THROUGH_RETRY_MAX
                );
                sleepBackoff();
            }
        }

        throw lastException == null
                ? new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "정책 Redis 즉시 갱신에 실패했습니다.")
                : lastException;
    }

    /**
     * 재시도 간 짧은 백오프를 수행합니다.
     * 인터럽트가 발생하면 현재 스레드 인터럽트 상태를 복구한 뒤 명시적 예외를 던집니다.
     */
    private void sleepBackoff() {
        try {
            Thread.sleep(WRITE_THROUGH_RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "정책 Redis 재시도 대기 중 인터럽트가 발생했습니다.");
        }
    }

    /**
     * repeat block 응답 DTO 목록을 Redis hash 포맷으로 변환합니다.
     * hash field: day:{dayNum}:{repeatBlockId}
     * hash value: {startSec}:{endSec}
     */
    private Map<String, String> buildRepeatBlockHash(List<RepeatBlockPolicyResDto> repeatBlocks) {
        Map<String, String> hashToWrite = new HashMap<>();
        if (repeatBlocks == null || repeatBlocks.isEmpty()) {
            return hashToWrite;
        }

        for (RepeatBlockPolicyResDto repeatBlock : repeatBlocks) {
            if (repeatBlock == null || !Boolean.TRUE.equals(repeatBlock.getIsActive())) {
                continue;
            }
            if (repeatBlock.getRepeatBlockId() == null || repeatBlock.getDays() == null) {
                continue;
            }

            for (RepeatBlockDayResDto day : repeatBlock.getDays()) {
                if (day == null || day.getDayOfWeek() == null || day.getStartAt() == null || day.getEndAt() == null) {
                    continue;
                }

                int dayNum = day.getDayOfWeek().ordinal();
                int startAtSec = day.getStartAt().toSecondOfDay();
                int endAtSec = day.getEndAt().toSecondOfDay();

                String field = "day:" + dayNum + ":" + repeatBlock.getRepeatBlockId();
                String value = startAtSec + ":" + endAtSec;
                hashToWrite.put(field, value);
            }
        }

        return hashToWrite;
    }

    /**
     * 한도 정책의 최종 저장값을 계산합니다.
     * 비활성 정책은 항상 -1(무제한)로 저장합니다.
     */
    private long resolveLimitValue(Long limit, Boolean isActive) {
        if (!Boolean.TRUE.equals(isActive)) {
            return -1L;
        }
        return limit == null ? -1L : limit;
    }

    /**
     * app_data_daily_limit hash의 field 이름 규칙입니다.
     */
    private String appDataLimitField(int appId) {
        return "limit:" + appId;
    }

    /**
     * app_speed_limit hash의 field 이름 규칙입니다.
     */
    private String appSpeedLimitField(int appId) {
        return "speed:" + appId;
    }
}
