package com.pooli.traffic.service.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.FailureKind;
import com.pooli.monitoring.metrics.TrafficRedisAvailabilityMetrics.RedisTarget;
import com.pooli.traffic.domain.TrafficLuaDeductExecutionResult;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.TrafficSharedPoolContributionLuaResult;
import com.pooli.traffic.domain.dto.response.TrafficLuaDeductResDto;
import com.pooli.traffic.domain.enums.TrafficLuaScriptType;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 트래픽 차감 및 리필에 사용하는 Lua 스크립트의 로딩과 실행을 담당하는 서비스입니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficLuaScriptInfraService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final ObjectMapper objectMapper;
    // cache Redis Lua 호출의 시도/실패 raw metric을 기록하는 전담 컴포넌트입니다.
    private final TrafficRedisAvailabilityMetrics trafficRedisAvailabilityMetrics;
    // Redis 예외를 timeout/connection/non-retryable metric tag로 분류할 때 사용합니다.
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    private final Map<TrafficLuaScriptType, String> scriptShaRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    private final Map<TrafficLuaScriptType, RedisScript<String>> stringScriptRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    private final Map<TrafficLuaScriptType, RedisScript<Long>> longScriptRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    private final Map<TrafficLuaScriptType, RedisScript<List>> listScriptRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    @PostConstruct
    /**
     * 애플리케이션 시작 시 Lua 스크립트를 등록하고 SHA를 미리 적재합니다.
     */
    public void preloadScripts() {
        for (TrafficLuaScriptType scriptType : TrafficLuaScriptType.values()) {
            String scriptText = loadScriptText(scriptType);
            registerScript(scriptType, scriptText);
            String sha = preloadScriptSha(scriptType, scriptText);

            log.info("traffic_lua_script_preloaded script={} sha={}", scriptType.getScriptName(), sha);
        }
    }

    /**
     * 차단성 정책 검증 Lua(block_policy_check.lua)를 실행합니다.
     *
     * <p>반환 의미:
     * answer=1(화이트리스트 우회), answer=0(일반), answer=-1(입력 오류)
     */
    public TrafficLuaExecutionResult executeBlockPolicyCheck(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.BLOCK_POLICY_CHECK, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.BLOCK_POLICY_CHECK);
    }

    /**
     * 차감 preflight에 필요한 정책 ready key, 개인 잔량 key, 공유 잔량 key 존재 여부를 한 Lua 호출로 조회합니다.
     *
     * @return 순서대로 정책 ready, 개인 잔량, 공유 잔량 key 존재 여부를 1/0 값으로 담은 목록입니다.
     */
    public List<Long> executePreflightKeyExistence(
            String linePolicyReadyKey,
            String individualBalanceKey,
            String sharedBalanceKey
    ) {
        List rawResult = executeListSingle(
                TrafficLuaScriptType.PREFLIGHT_KEY_EXISTENCE,
                List.of(linePolicyReadyKey, individualBalanceKey, sharedBalanceKey),
                List.of()
        );
        if (rawResult.size() != 3) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua preflight key existence result size is invalid."
            );
        }
        return rawResult.stream()
                .map(this::toLongResult)
                .toList();
    }

    /**
     * 개인+공유+QoS 단일 차감 Lua 스크립트를 실행합니다.
     */
    public TrafficLuaDeductExecutionResult executeDeductUnified(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_UNIFIED, keys, args);
        return parseUnifiedDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_UNIFIED);
    }

    /**
     * 개인풀 월별 잔량 snapshot hydrate Lua 스크립트를 실행합니다.
     */
    public long executeHydrateIndividualSnapshot(
            String balanceKey,
            long amount,
            long qos,
            long expireAtEpochSeconds
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.HYDRATE_INDIVIDUAL_SNAPSHOT,
                List.of(balanceKey),
                List.of(String.valueOf(amount), String.valueOf(qos), String.valueOf(expireAtEpochSeconds))
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * 공유풀 월별 잔량 snapshot hydrate Lua 스크립트를 실행합니다.
     */
    public long executeHydrateSharedSnapshot(String balanceKey, long amount, long expireAtEpochSeconds) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.HYDRATE_SHARED_SNAPSHOT,
                List.of(balanceKey),
                List.of(String.valueOf(amount), String.valueOf(expireAtEpochSeconds))
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * 락 해제 Lua 스크립트를 실행합니다.
     */
    public boolean executeLockRelease(String lockKey, String lockOwner) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.LOCK_RELEASE,
                List.of(lockKey),
                List.of(lockOwner)
        );

        return rawResult == 1L;
    }

    /**
     * hydrate와 Redis-first 기여 흐름이 공유하는 owner 단위 lock을 획득합니다.
     *
     * <p>lockKey는 `TrafficRedisKeyFactory`가 만든 고정 Redis key이며,
     * UUID는 compare-and-delete 해제를 위한 lock value(owner token)로만 저장합니다.
     */
    public Optional<HydrateLockHandle> tryAcquireHydrateLock(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            return Optional.empty();
        }

        String lockOwner = "hydrate-lock-owner:" + UUID.randomUUID();
        try {
            trafficRedisAvailabilityMetrics.incrementOperation(RedisTarget.CACHE);
            Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockOwner,
                    Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
            );
            if (!Boolean.TRUE.equals(acquired)) {
                return Optional.empty();
            }
            return Optional.of(new HydrateLockHandle(lockKey, lockOwner));
        } catch (DataAccessException e) {
            trafficRedisAvailabilityMetrics.incrementFailure(RedisTarget.CACHE, resolveFailureKind(e));
            log.error("traffic_hydrate_lock_acquire_failed lockKey={}", lockKey, e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
    }

    /**
     * 개인/공유 hydrate lock을 모두 획득합니다. 일부 획득 후 실패하거나 예외가 발생하면 이미 잡은 lock을 즉시 해제합니다.
     */
    public Optional<HydrateLockPair> tryAcquireHydrateLocks(String individualLockKey, String sharedLockKey) {
        Optional<HydrateLockHandle> individualLock = tryAcquireHydrateLock(individualLockKey);
        if (individualLock.isEmpty()) {
            return Optional.empty();
        }

        try {
            Optional<HydrateLockHandle> sharedLock = tryAcquireHydrateLock(sharedLockKey);
            if (sharedLock.isEmpty()) {
                releaseHydrateLock(individualLock.get());
                return Optional.empty();
            }

            return Optional.of(new HydrateLockPair(individualLock.get(), sharedLock.get()));
        } catch (RuntimeException e) {
            try {
                releaseHydrateLock(individualLock.get());
            } catch (RuntimeException releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
    }

    /**
     * lock owner 값이 일치할 때만 hydrate lock을 해제합니다.
     */
    public boolean releaseHydrateLock(HydrateLockHandle lockHandle) {
        if (lockHandle == null) {
            return false;
        }
        return executeLockRelease(lockHandle.lockKey(), lockHandle.lockOwner());
    }

    /**
     * 개인/공유 hydrate lock pair를 해제합니다.
     */
    public void releaseHydrateLocks(HydrateLockPair lockPair) {
        if (lockPair == null) {
            return;
        }
        releaseHydrateLock(lockPair.individualLock());
        releaseHydrateLock(lockPair.sharedLock());
    }

    /**
     * 공유풀 기여 정식 Redis 적용 Lua를 실행합니다.
     */
    public TrafficSharedPoolContributionLuaResult executeSharedPoolContributionApply(
            String metadataKey,
            String individualBalanceKey,
            String sharedBalanceKey,
            String traceId,
            long amount,
            boolean individualUnlimited
    ) {
        String rawJson = executeStringSingle(
                TrafficLuaScriptType.SHARED_POOL_CONTRIBUTION_APPLY,
                List.of(metadataKey, individualBalanceKey, sharedBalanceKey),
                List.of(traceId, String.valueOf(amount), individualUnlimited ? "1" : "0")
        );
        return parseSharedPoolContributionResult(rawJson, TrafficLuaScriptType.SHARED_POOL_CONTRIBUTION_APPLY);
    }

    /**
     * 공유풀 기여 outbox 복구 Lua를 실행합니다.
     */
    public TrafficSharedPoolContributionLuaResult executeSharedPoolContributionRecover(
            String metadataKey,
            String individualBalanceKey,
            String sharedBalanceKey,
            boolean individualUnlimited
    ) {
        String rawJson = executeStringSingle(
                TrafficLuaScriptType.SHARED_POOL_CONTRIBUTION_RECOVER,
                List.of(metadataKey, individualBalanceKey, sharedBalanceKey),
                List.of(individualUnlimited ? "1" : "0")
        );
        return parseSharedPoolContributionResult(rawJson, TrafficLuaScriptType.SHARED_POOL_CONTRIBUTION_RECOVER);
    }

    /**
     * 공유풀 기여 metadata를 삭제하고 hydrate lock을 owner 검증 후 해제합니다.
     */
    public long executeSharedPoolContributionCleanup(
            String metadataKey,
            HydrateLockPair lockPair
    ) {
        if (lockPair == null) {
            return 0L;
        }
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.SHARED_POOL_CONTRIBUTION_CLEANUP,
                List.of(metadataKey, lockPair.individualLock().lockKey(), lockPair.sharedLock().lockKey()),
                List.of(lockPair.individualLock().lockOwner(), lockPair.sharedLock().lockOwner())
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * in-flight 멱등 hash를 키 미존재 시 생성합니다.
     *
     * @return 1이면 이번 호출에서 생성됨, 0이면 기존 키 존재
     */
    public long executeInFlightCreateIfAbsent(
            String dedupeKey,
            String processedIndividualField,
            String processedSharedField,
            String processedQosField,
            String retryField,
            String defaultValue
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.IN_FLIGHT_CREATE_IF_ABSENT,
                List.of(dedupeKey),
                List.of(
                        processedIndividualField,
                        processedSharedField,
                        processedQosField,
                        retryField,
                        defaultValue
                )
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * in-flight 멱등 hash의 retryCount를 1 증가시킵니다.
     * 키가 없으면 기본 필드를 초기화한 후 증가합니다.
     */
    public long executeInFlightIncrementRetryWithInit(
            String dedupeKey,
            String processedIndividualField,
            String processedSharedField,
            String processedQosField,
            String retryField,
            String defaultValue
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.IN_FLIGHT_INCREMENT_RETRY_WITH_INIT,
                List.of(dedupeKey),
                List.of(
                        processedIndividualField,
                        processedSharedField,
                        processedQosField,
                        retryField,
                        defaultValue
                )
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * Redis 장애 복구 replay Lua를 실행한다.
     */
    public List<String> executeRestoreUsageReplay(List<String> keys, List<String> args) {
        List rawResult = executeListSingle(TrafficLuaScriptType.RESTORE_USAGE_REPLAY, keys, args);
        return rawResult.stream()
                .map(String::valueOf)
                .toList();
    }

    /**
     * Redis 장애 복구 검증 보정 Lua를 실행한다.
     */
    public List<String> executeRestoreUsageCorrection(
            String key,
            String valueKind,
            String field,
            long expectedValue,
            long expireEpochSeconds
    ) {
        List rawResult = executeListSingle(
                TrafficLuaScriptType.RESTORE_USAGE_CORRECTION,
                List.of(key),
                List.of(valueKind, field, String.valueOf(expectedValue), String.valueOf(expireEpochSeconds))
        );
        return rawResult.stream()
                .map(String::valueOf)
                .toList();
    }

    /**
     * 미리 적재한 Lua 스크립트의 SHA를 반환합니다.
     */
    public String getPreloadedSha(TrafficLuaScriptType scriptType) {
        return scriptShaRegistry.get(scriptType);
    }

    /**
     * 문자열 결과를 반환하는 Lua 스크립트를 실행합니다.
     *
     * <p>1. 스크립트 타입에 맞는 문자열 반환 RedisScript를 조회합니다.
     * <br>2. cache Redis 명령 시도 metric을 먼저 증가시킵니다.
     * <br>3. Redis Lua를 실행하고 null 결과는 내부 오류로 처리합니다.
     * <br>4. Redis 접근 예외가 발생하면 실패 유형 metric을 기록한 뒤 외부 시스템 오류로 래핑합니다.
     */
    private String executeStringSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<String> script = requireStringScript(scriptType);

        try {
            // alert rule의 분모가 되는 cache Redis 명령 시도 수를 기록합니다.
            trafficRedisAvailabilityMetrics.incrementOperation(RedisTarget.CACHE);
            String result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua script returned null result.");
            }

            return result;
        } catch (DataAccessException e) {
            // alert rule의 분자가 되는 Redis 실패 수를 timeout/connection/non-retryable로 분리해 기록합니다.
            trafficRedisAvailabilityMetrics.incrementFailure(RedisTarget.CACHE, resolveFailureKind(e));
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
    }

    /**
     * 정수 결과를 반환하는 Lua 스크립트를 실행합니다.
     *
     * <p>1. 스크립트 타입에 맞는 정수 반환 RedisScript를 조회합니다.
     * <br>2. cache Redis 명령 시도 metric을 먼저 증가시킵니다.
     * <br>3. Redis Lua를 실행하고 null 결과는 내부 오류로 처리합니다.
     * <br>4. Redis 접근 예외가 발생하면 실패 유형 metric을 기록한 뒤 외부 시스템 오류로 래핑합니다.
     */
    private Long executeLongSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<Long> script = requireLongScript(scriptType);

        try {
            // hydrate/lock/in-flight Lua도 같은 cache Redis 가용성 지표의 요청 수에 포함합니다.
            trafficRedisAvailabilityMetrics.incrementOperation(RedisTarget.CACHE);
            Long result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua script returned null result.");
            }

            return result;
        } catch (DataAccessException e) {
            // non-retryable은 별도 집계하되 Prometheus 실패율 rule에서는 제외합니다.
            trafficRedisAvailabilityMetrics.incrementFailure(RedisTarget.CACHE, resolveFailureKind(e));
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
    }

    /**
     * 목록 결과를 반환하는 Lua 스크립트를 실행합니다.
     */
    private List executeListSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<List> script = requireListScript(scriptType);

        try {
            // 1. cache Redis 명령 시도 시 가용성 측정을 위한 오퍼레이션 카운트를 증가시키고 Lua 스크립트를 실행한다.
            trafficRedisAvailabilityMetrics.incrementOperation(RedisTarget.CACHE);
            List result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            // 2. 실행 결과가 null일 경우 정상적이지 않은 응답이므로 내부 서버 오류 예외(500)를 던진다.
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua script returned null result.");
            }
            // 4. 정상적으로 반환된 목록 결과를 호출처에 반환한다.
            return result;
        } catch (DataAccessException e) {
            // 3. Redis 호출 중 예외(DataAccessException)가 발생하면 에러 메트릭을 분류하여 기록하고 로그를 출력한 후 외부 시스템 오류로 변환하여 예외를 발생시킨다.
            trafficRedisAvailabilityMetrics.incrementFailure(RedisTarget.CACHE, resolveFailureKind(e));
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
    }

    /**
     * Redis Lua 목록 결과의 정수 값을 Java long으로 변환합니다.
     */
    private long toLongResult(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * Redis 예외를 EM6 alert rule tag 계약에 맞는 실패 유형으로 변환합니다.
     *
     * <p>1. timeout 계열이면 `timeout`으로 분류합니다.
     * <br>2. connection 계열이면 `connection`으로 분류합니다.
     * <br>3. 둘 다 아니면 alert 실패율에서 제외할 `non_retryable`로 분류합니다.
     */
    private FailureKind resolveFailureKind(RuntimeException failure) {
        if (trafficRedisFailureClassifier.isTimeoutFailure(failure)) {
            return FailureKind.TIMEOUT;
        }
        if (trafficRedisFailureClassifier.isConnectionFailure(failure)) {
            return FailureKind.CONNECTION;
        }
        return FailureKind.NON_RETRYABLE;
    }

    /**
     * 차감 Lua 결과 JSON을 파싱하고 유효성을 검증합니다.
     */
    private TrafficLuaExecutionResult parseDeductResult(String rawJson, TrafficLuaScriptType scriptType) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua deduct result is empty. script=" + scriptType.getScriptName()
            );
        }

        try {
            TrafficLuaDeductResDto parsedResult = objectMapper.readValue(rawJson, TrafficLuaDeductResDto.class);
            if (parsedResult.getStatus() == null) {
                throw new ApplicationException(
                        CommonErrorCode.INTERNAL_SERVER_ERROR,
                        "Lua deduct status is missing. script=" + scriptType.getScriptName()
                );
            }

            return TrafficLuaExecutionResult.builder()
                    .answer(parsedResult.getAnswer())
                    .status(parsedResult.getStatus())
                    .build();
        } catch (JsonProcessingException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to parse Lua JSON result. script=" + scriptType.getScriptName()
            );
        }
    }

    /**
     * 단일 차감 Lua 결과 JSON을 파싱하고 유효성을 검증합니다.
     */
    private TrafficLuaDeductExecutionResult parseUnifiedDeductResult(String rawJson, TrafficLuaScriptType scriptType) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua deduct result is empty. script=" + scriptType.getScriptName()
            );
        }

        try {
            TrafficLuaDeductResDto parsedResult = objectMapper.readValue(rawJson, TrafficLuaDeductResDto.class);
            if (parsedResult.getStatus() == null) {
                throw new ApplicationException(
                        CommonErrorCode.INTERNAL_SERVER_ERROR,
                        "Lua deduct status is missing. script=" + scriptType.getScriptName()
                );
            }

            return TrafficLuaDeductExecutionResult.builder()
                    .indivDeducted(parsedResult.getIndivDeducted())
                    .sharedDeducted(parsedResult.getSharedDeducted())
                    .qosDeducted(parsedResult.getQosDeducted())
                    .finishedAtEpochMillis(parsedResult.getFinishedAtEpochMillis())
                    .status(parsedResult.getStatus())
                    .build();
        } catch (JsonProcessingException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to parse Lua JSON result. script=" + scriptType.getScriptName()
            );
        }
    }

    /**
     * 문자열 반환용 Lua 스크립트가 등록되어 있는지 확인합니다.
     */
    private RedisScript<String> requireStringScript(TrafficLuaScriptType scriptType) {
        RedisScript<String> script = stringScriptRegistry.get(scriptType);
        if (script == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua string script is not registered. script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
     * 정수 반환용 Lua 스크립트가 등록되어 있는지 확인합니다.
     */
    private RedisScript<Long> requireLongScript(TrafficLuaScriptType scriptType) {
        RedisScript<Long> script = longScriptRegistry.get(scriptType);
        if (script == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua long script is not registered. script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
     * 목록 반환용 Lua 스크립트가 등록되어 있는지 확인합니다.
     */
    private RedisScript<List> requireListScript(TrafficLuaScriptType scriptType) {
        RedisScript<List> script = listScriptRegistry.get(scriptType);
        if (script == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua list script is not registered. script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
     * 스크립트 타입에 맞는 RedisScript 레지스트리에 등록합니다.
     */
    private void registerScript(TrafficLuaScriptType scriptType, String scriptText) {
        switch (scriptType) {
            case BLOCK_POLICY_CHECK, DEDUCT_UNIFIED -> {
                DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(String.class);
                stringScriptRegistry.put(scriptType, redisScript);
            }
            case SHARED_POOL_CONTRIBUTION_APPLY,
                 SHARED_POOL_CONTRIBUTION_RECOVER -> {
                DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(String.class);
                stringScriptRegistry.put(scriptType, redisScript);
            }
            case HYDRATE_INDIVIDUAL_SNAPSHOT,
                 HYDRATE_SHARED_SNAPSHOT,
                 LOCK_RELEASE,
                 IN_FLIGHT_CREATE_IF_ABSENT,
                 IN_FLIGHT_INCREMENT_RETRY_WITH_INIT,
                 SHARED_POOL_CONTRIBUTION_CLEANUP -> {
                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(Long.class);
                longScriptRegistry.put(scriptType, redisScript);
            }
            case RESTORE_USAGE_REPLAY,
                 RESTORE_USAGE_CORRECTION -> {
                DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(List.class);
                listScriptRegistry.put(scriptType, redisScript);
            }
        }
    }

    /**
     * Lua 스크립트를 Redis에 preload하고 SHA를 저장합니다.
     */
    private String preloadScriptSha(TrafficLuaScriptType scriptType, String scriptText) {
        try {
            String sha = cacheStringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.scriptingCommands().scriptLoad(scriptText.getBytes(StandardCharsets.UTF_8))
            );

            if (sha == null || sha.isBlank()) {
                throw new ApplicationException(
                        CommonErrorCode.EXTERNAL_SYSTEM_ERROR,
                        "Lua SHA preload returned empty value. script=" + scriptType.getScriptName()
                );
            }

            scriptShaRegistry.put(scriptType, sha);
            return sha;
        } catch (DataAccessException e) {
            log.error("traffic_lua_preload_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua SHA preload failed.");
        }
    }

    /**
     * classpath에서 Lua 스크립트 본문을 읽어옵니다.
     */
    private String loadScriptText(TrafficLuaScriptType scriptType) {
        ClassPathResource resource = new ClassPathResource(scriptType.getResourcePath());

        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to load Lua script text. script=" + scriptType.getScriptName()
            );
        }
    }

    /**
     * 공유풀 기여 Lua 결과 JSON을 파싱하고 유효성을 검증합니다.
     */
    private TrafficSharedPoolContributionLuaResult parseSharedPoolContributionResult(
            String rawJson,
            TrafficLuaScriptType scriptType
    ) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua shared pool contribution result is empty. script=" + scriptType.getScriptName()
            );
        }

        try {
            TrafficSharedPoolContributionLuaResult parsedResult =
                    objectMapper.readValue(rawJson, TrafficSharedPoolContributionLuaResult.class);
            if (parsedResult.getStatus() == null || parsedResult.getStatus().isBlank()) {
                throw new ApplicationException(
                        CommonErrorCode.INTERNAL_SERVER_ERROR,
                        "Lua shared pool contribution status is missing. script=" + scriptType.getScriptName()
                );
            }
            return parsedResult;
        } catch (JsonProcessingException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to parse Lua shared pool contribution JSON result. script=" + scriptType.getScriptName()
            );
        }
    }

    /**
     * 획득한 hydrate lock의 Redis key와 해제 검증용 owner token입니다.
     */
    public record HydrateLockHandle(String lockKey, String lockOwner) {
    }

    /**
     * 공유풀 기여처럼 개인/공유 owner lock을 동시에 잡아야 하는 흐름에서 사용하는 lock 묶음입니다.
     */
    public record HydrateLockPair(HydrateLockHandle individualLock, HydrateLockHandle sharedLock) {
    }

}
