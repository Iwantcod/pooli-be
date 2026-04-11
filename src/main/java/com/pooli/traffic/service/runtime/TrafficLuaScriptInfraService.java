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
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.response.TrafficLuaDeductResDto;
import com.pooli.traffic.domain.enums.TrafficLuaScriptType;
import com.pooli.traffic.domain.enums.TrafficRefillGateStatus;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 트래픽 차감 및 리필에 사용하는 Lua 스크립트의 로딩과 실행을 담당하는 서비스입니다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficLuaScriptInfraService {

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final ObjectMapper objectMapper;

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
     * 개인풀 차감 Lua 스크립트를 실행합니다.
     */
    public TrafficLuaExecutionResult executePolicyCheckIndividual(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.POLICY_CHECK_INDIVIDUAL, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.POLICY_CHECK_INDIVIDUAL);
    }

    /**
     * 공유풀 차단성 정책 검증 Lua 스크립트를 실행합니다.
     */
    public TrafficLuaExecutionResult executePolicyCheckShared(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.POLICY_CHECK_SHARED, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.POLICY_CHECK_SHARED);
    }

    /**
     * 개인풀 차감 Lua 스크립트를 실행합니다.
     */
    public TrafficLuaExecutionResult executeDeductIndividual(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_INDIVIDUAL, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_INDIVIDUAL);
    }

    /**
     * 공유풀 차감 Lua 스크립트를 실행합니다.
     */
    public TrafficLuaExecutionResult executeDeductShared(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_SHARED, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_SHARED);
    }

    /**
     * 리필 가능 여부를 판단하는 gate Lua 스크립트를 실행합니다.
     */
    public TrafficRefillGateStatus executeRefillGate(
            String lockKey,
            String balanceKey,
            String traceId,
            long lockTtlMs,
            long currentAmount,
            long threshold
    ) {
        String statusText = executeStringSingle(
                TrafficLuaScriptType.REFILL_GATE,
                List.of(lockKey, balanceKey),
                List.of(
                        traceId,
                        String.valueOf(lockTtlMs),
                        String.valueOf(threshold)
                )
        );

        try {
            // 점진 배포 중 구버전 Lua가 "SKIP" 단일 상태를 반환할 수 있어 하위 호환 매핑을 유지합니다.
            if ("SKIP".equals(statusText)) {
                return TrafficRefillGateStatus.SKIP_THRESHOLD;
            }
            return TrafficRefillGateStatus.valueOf(statusText);
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to parse refill gate status. status=" + statusText
            );
        }
    }

    /**
     * 락 소유 여부를 heartbeat Lua 스크립트로 확인합니다.
     */
    public boolean executeLockHeartbeat(String lockKey, String traceId, long lockTtlMs) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.LOCK_HEARTBEAT,
                List.of(lockKey),
                List.of(traceId, String.valueOf(lockTtlMs))
        );

        return rawResult == 1L;
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
            String processedField,
            String processedDefaultValue,
            String retryField,
            String retryDefaultValue
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.IN_FLIGHT_CREATE_IF_ABSENT,
                List.of(dedupeKey),
                List.of(
                        processedField,
                        processedDefaultValue,
                        retryField,
                        retryDefaultValue
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
            String processedField,
            String processedDefaultValue,
            String retryField,
            String retryDefaultValue
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.IN_FLIGHT_INCREMENT_RETRY_WITH_INIT,
                List.of(dedupeKey),
                List.of(
                        processedField,
                        processedDefaultValue,
                        retryField,
                        retryDefaultValue
                )
        );
        return rawResult == null ? 0L : rawResult;
    }

    /**
     * in-flight 멱등 hash의 processedData를 delta만큼 증가시킵니다.
     * 키가 없으면 기본 필드를 초기화한 후 증가합니다.
     */
    public long executeInFlightIncrementProcessedWithInit(
            String dedupeKey,
            String processedField,
            String processedDefaultValue,
            String retryField,
            String retryDefaultValue,
            long delta
    ) {
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.IN_FLIGHT_INCREMENT_PROCESSED_WITH_INIT,
                List.of(dedupeKey),
                List.of(
                        processedField,
                        processedDefaultValue,
                        retryField,
                        retryDefaultValue,
                        String.valueOf(delta)
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
     */
    private String executeStringSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<String> script = requireStringScript(scriptType);

        try {
            String result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua script returned null result.");
            }

            return result;
        } catch (DataAccessException e) {
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
    }

    /**
     * 정수 결과를 반환하는 Lua 스크립트를 실행합니다.
     */
    private Long executeLongSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<Long> script = requireLongScript(scriptType);

        try {
            Long result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua script returned null result.");
            }

            return result;
        } catch (DataAccessException e) {
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, e);
        }
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
            case POLICY_CHECK_INDIVIDUAL, POLICY_CHECK_SHARED, DEDUCT_INDIVIDUAL, DEDUCT_SHARED, REFILL_GATE -> {
                DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(String.class);
                stringScriptRegistry.put(scriptType, redisScript);
            }
            case LOCK_HEARTBEAT,
                 LOCK_RELEASE,
                 IN_FLIGHT_CREATE_IF_ABSENT,
                 IN_FLIGHT_INCREMENT_RETRY_WITH_INIT,
                 IN_FLIGHT_INCREMENT_PROCESSED_WITH_INIT -> {
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
