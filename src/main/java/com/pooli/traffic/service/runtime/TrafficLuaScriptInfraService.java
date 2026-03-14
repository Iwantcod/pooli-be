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
     */
    public TrafficLuaExecutionResult executeDeductIndividual(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_INDIVIDUAL, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_INDIVIDUAL);
    }

    /**
     */
    public TrafficLuaExecutionResult executeDeductShared(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_SHARED, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_SHARED);
    }

    /**
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
            return TrafficRefillGateStatus.valueOf(statusText);
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to parse refill gate status. status=" + statusText
            );
        }
    }

    /**
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
     */
    public String getPreloadedSha(TrafficLuaScriptType scriptType) {
        return scriptShaRegistry.get(scriptType);
    }

    /**
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
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua execution failed.");
        }
    }

    /**
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
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua execution failed.");
        }
    }

    /**
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
     */
    private void registerScript(TrafficLuaScriptType scriptType, String scriptText) {
        switch (scriptType) {
            case DEDUCT_INDIVIDUAL, DEDUCT_SHARED -> {
                DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(String.class);
                stringScriptRegistry.put(scriptType, redisScript);
            }
            case REFILL_GATE -> {
                DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(String.class);
                stringScriptRegistry.put(scriptType, redisScript);
            }
            case LOCK_HEARTBEAT, LOCK_RELEASE -> {
                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(scriptText);
                redisScript.setResultType(Long.class);
                longScriptRegistry.put(scriptType, redisScript);
            }
        }
    }

    /**
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
