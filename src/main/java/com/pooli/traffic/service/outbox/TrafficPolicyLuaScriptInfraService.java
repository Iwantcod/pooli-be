package com.pooli.traffic.service.outbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.enums.TrafficPolicyLuaScriptType;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryExecutionResult;
import com.pooli.traffic.service.retry.TrafficRedisCasRetryInvoker;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 정책 Redis 동기화용 Lua 스크립트의 로딩과 실행을 담당합니다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficPolicyLuaScriptInfraService {

    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final TrafficRedisCasRetryInvoker trafficRedisCasRetryInvoker;

    private final Map<TrafficPolicyLuaScriptType, RedisScript<Long>> longScriptRegistry =
            new EnumMap<>(TrafficPolicyLuaScriptType.class);

    /**
     * 애플리케이션 시작 시 policy CAS Lua 스크립트를 classpath에서 읽어 등록합니다.
     */
    @PostConstruct
    public void initializeScripts() {
        for (TrafficPolicyLuaScriptType scriptType : TrafficPolicyLuaScriptType.values()) {
            longScriptRegistry.put(scriptType, buildLongScript(loadScriptText(scriptType)));
        }
    }

    /**
     * policy CAS Lua 스크립트를 실행하고 기존 PolicySyncResult 계약으로 변환합니다.
     */
    public PolicySyncResult executeLongScript(
            TrafficPolicyLuaScriptType scriptType,
            List<String> keys,
            Object... args
    ) {
        RedisScript<Long> script = requireLongScript(scriptType);
        try {
            TrafficRedisCasRetryExecutionResult retryExecutionResult =
                    trafficRedisCasRetryInvoker.execute(script, keys, args);
            return mapRetryExecutionResult(retryExecutionResult);
        } catch (DataAccessException e) {
            return mapDataAccessException(e);
        }
    }

    /**
     * 초기화 누락을 빠르게 감지하기 위해 스크립트 존재를 검증합니다.
     */
    private RedisScript<Long> requireLongScript(TrafficPolicyLuaScriptType scriptType) {
        RedisScript<Long> script = longScriptRegistry.get(scriptType);
        if (script == null) {
            throw new IllegalStateException(
                    "Policy Lua script is not initialized. script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
     * Retry 실행 결과를 기존 PolicySyncResult 계약으로 변환합니다.
     */
    private PolicySyncResult mapRetryExecutionResult(TrafficRedisCasRetryExecutionResult retryExecutionResult) {
        DataAccessException lastFailure = retryExecutionResult.lastFailure();
        if (lastFailure != null) {
            return mapDataAccessException(lastFailure);
        }
        return mapRawResult(retryExecutionResult.rawResult());
    }

    /**
     * Redis DataAccessException을 CONNECTION/RETRYABLE 경계로 분류합니다.
     */
    private PolicySyncResult mapDataAccessException(DataAccessException exception) {
        if (trafficRedisFailureClassifier.isConnectionFailure(exception)) {
            return PolicySyncResult.CONNECTION_FAILURE;
        }
        return PolicySyncResult.RETRYABLE_FAILURE;
    }

    /**
     * CAS Lua raw result를 기존 상태 계약으로 매핑합니다.
     */
    private PolicySyncResult mapRawResult(Long rawResult) {
        if (rawResult == null) {
            return PolicySyncResult.RETRYABLE_FAILURE;
        }
        if (rawResult == 1L) {
            return PolicySyncResult.SUCCESS;
        }
        if (rawResult == 0L) {
            return PolicySyncResult.STALE_REJECTED;
        }
        return PolicySyncResult.RETRYABLE_FAILURE;
    }

    /**
     * classpath에서 Lua 스크립트 본문을 읽어옵니다.
     */
    private String loadScriptText(TrafficPolicyLuaScriptType scriptType) {
        ClassPathResource resource = new ClassPathResource(scriptType.getResourcePath());
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load policy Lua script text. script=" + scriptType.getScriptName(),
                    e
            );
        }
    }

    /**
     * Long 반환 스크립트 객체를 생성합니다.
     */
    private static RedisScript<Long> buildLongScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }
}
