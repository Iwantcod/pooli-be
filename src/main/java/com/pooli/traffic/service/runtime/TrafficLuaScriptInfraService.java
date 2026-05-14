package com.pooli.traffic.service.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    public boolean executeLockRelease(String lockKey, String traceId) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.LOCK_RELEASE,
                List.of(lockKey),
                List.of(traceId)
        );

        return rawResult == 1L;
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
            case HYDRATE_INDIVIDUAL_SNAPSHOT,
                 HYDRATE_SHARED_SNAPSHOT,
                 LOCK_RELEASE,
                 IN_FLIGHT_CREATE_IF_ABSENT,
                 IN_FLIGHT_INCREMENT_RETRY_WITH_INIT -> {
                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(Long.class);
                longScriptRegistry.put(scriptType, redisScript);
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

}
