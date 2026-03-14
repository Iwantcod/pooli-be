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
 * ?몃옒??李④컧 Lua ?ㅽ겕由쏀듃??preload(SHA)? ?ㅽ뻾???대떦?섎뒗 ?명봽???쒕퉬?ㅼ엯?덈떎.
 * ?ㅽ겕由쏀듃蹂??ㅽ뻾 硫붿꽌?쒕? ?쒓났???ㅼ??ㅽ듃?덉씠?곌? ?쇨???怨꾩빟?쇰줈 Lua瑜??몄텧?????덇쾶 ?⑸땲??
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficLuaScriptInfraService {

    // Lua瑜??ㅽ뻾??Redis ?몄뒪?댁뒪(?몃옒???뺤콉/移댁슫????μ냼)
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    // 李④컧 寃곌낵 JSON??DTO濡???쭅?ы솕?????ъ슜?섎뒗 留ㅽ띁
    private final ObjectMapper objectMapper;

    // preload 寃곌낵濡?諛쏆? SHA1??湲곕줉???댁쁺 濡쒓렇/?먭????쒖슜?쒕떎.
    private final Map<TrafficLuaScriptType, String> scriptShaRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    // ?⑥씪 臾몄옄??寃곌낵瑜?諛섑솚?섎뒗 ?ㅽ겕由쏀듃 ?덉??ㅽ듃由?
    private final Map<TrafficLuaScriptType, RedisScript<String>> stringScriptRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    // ?⑥씪 ?レ옄 寃곌낵瑜?諛섑솚?섎뒗 ?ㅽ겕由쏀듃 ?덉??ㅽ듃由?
    private final Map<TrafficLuaScriptType, RedisScript<Long>> longScriptRegistry =
            new EnumMap<>(TrafficLuaScriptType.class);

    @PostConstruct
    /**
      * `preloadScripts` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    public void preloadScripts() {
        // ?좏뵆由ъ??댁뀡 ?쒖옉 ??紐⑤뱺 Lua瑜?硫붾え由ъ뿉 ?щ━怨?Redis??SCRIPT LOAD ?대몦??
        // ?댄썑 ?ㅽ뻾 ??EVALSHA 寃쎈줈瑜??곗꽑 ?ъ슜??泥??몄텧 吏?곗쓣 以꾩씤??
        for (TrafficLuaScriptType scriptType : TrafficLuaScriptType.values()) {
            String scriptText = loadScriptText(scriptType);
            registerScript(scriptType, scriptText);
            String sha = preloadScriptSha(scriptType, scriptText);

            log.info("traffic_lua_script_preloaded script={} sha={}", scriptType.getScriptName(), sha);
        }
    }

    /**
     * 媛쒖씤? 李④컧 Lua瑜??ㅽ뻾?⑸땲??
     * keys/args 援ъ꽦? ?몄텧??TrafficHydrateRefillAdapterService)媛 紐낆꽭 ?쒖꽌?濡??꾨떖?⑸땲??
     */
    public TrafficLuaExecutionResult executeDeductIndividual(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_INDIVIDUAL, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_INDIVIDUAL);
    }

    /**
     * 怨듭쑀? 李④컧 Lua瑜??ㅽ뻾?⑸땲??
     * keys/args 援ъ꽦? ?몄텧??TrafficHydrateRefillAdapterService)媛 紐낆꽭 ?쒖꽌?濡??꾨떖?⑸땲??
     */
    public TrafficLuaExecutionResult executeDeductShared(List<String> keys, List<String> args) {
        String rawJson = executeStringSingle(TrafficLuaScriptType.DEDUCT_SHARED, keys, args);
        return parseDeductResult(rawJson, TrafficLuaScriptType.DEDUCT_SHARED);
    }

    /**
     * ?듭떖 泥섎━ 濡쒖쭅???ㅽ뻾?섍퀬 寃곌낵瑜?諛섑솚?⑸땲??
     */
    public TrafficRefillGateStatus executeRefillGate(
            String lockKey,
            String balanceKey,
            String traceId,
            long lockTtlMs,
            long currentAmount,
            long threshold
    ) {
        // refill gate???⑥씪 臾몄옄???곹깭(FAIL/SKIP/OK/WAIT)瑜?諛섑솚?쒕떎.
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
                    "refill gate ?곹깭 ?뚯떛???ㅽ뙣?덉뒿?덈떎. status=" + statusText
            );
        }
    }

    /**
     * ?듭떖 泥섎━ 濡쒖쭅???ㅽ뻾?섍퀬 寃곌낵瑜?諛섑솚?⑸땲??
     */
    public boolean executeLockHeartbeat(String lockKey, String traceId, long lockTtlMs) {
        // lock heartbeat??1/0??諛섑솚?섎?濡?1?대㈃ ?깃났(true)?쇰줈 蹂?섑븳??
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.LOCK_HEARTBEAT,
                List.of(lockKey),
                List.of(traceId, String.valueOf(lockTtlMs))
        );

        return rawResult == 1L;
    }

    /**
     * ?듭떖 泥섎━ 濡쒖쭅???ㅽ뻾?섍퀬 寃곌낵瑜?諛섑솚?⑸땲??
     */
    public boolean executeLockRelease(String lockKey, String traceId) {
        // lock release ??떆 1/0 怨꾩빟?대?濡?1?대㈃ ?ㅼ젣 ?댁젣 ?깃났?쇰줈 ?먮떒?쒕떎.
        Long rawResult = executeLongSingle(
                TrafficLuaScriptType.LOCK_RELEASE,
                List.of(lockKey),
                List.of(traceId)
        );

        return rawResult == 1L;
    }

    /**
     * ?꾩옱 ?ㅼ젙/?곹깭 媛믪쓣 諛섑솚?⑸땲??
     */
    public String getPreloadedSha(TrafficLuaScriptType scriptType) {
        // ?댁쁺 ?먭?/濡쒓렇 紐⑹쟻??議고쉶 硫붿꽌?쒕떎.
        return scriptShaRegistry.get(scriptType);
    }

    /**
     * ?듭떖 泥섎━ 濡쒖쭅???ㅽ뻾?섍퀬 寃곌낵瑜?諛섑솚?⑸땲??
     */
    private String executeStringSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<String> script = requireStringScript(scriptType);

        try {
            // 臾몄옄???⑥씪媛?怨꾩빟??洹몃?濡?諛섑솚?쒕떎.
            String result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua ?ㅽ뻾 寃곌낵媛 鍮꾩뼱 ?덉뒿?덈떎.");
            }

            return result;
        } catch (DataAccessException e) {
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua ?ㅽ뻾???ㅽ뙣?덉뒿?덈떎.");
        }
    }

    /**
     * ?듭떖 泥섎━ 濡쒖쭅???ㅽ뻾?섍퀬 寃곌낵瑜?諛섑솚?⑸땲??
     */
    private Long executeLongSingle(TrafficLuaScriptType scriptType, List<String> keys, List<String> args) {
        RedisScript<Long> script = requireLongScript(scriptType);

        try {
            // ?レ옄 ?⑥씪媛?怨꾩빟(二쇰줈 1/0)??Long?쇰줈 諛쏆븘 泥섎━?쒕떎.
            Long result = cacheStringRedisTemplate.execute(script, keys, args.toArray());
            if (result == null) {
                throw new ApplicationException(CommonErrorCode.INTERNAL_SERVER_ERROR, "Lua ?ㅽ뻾 寃곌낵媛 鍮꾩뼱 ?덉뒿?덈떎.");
            }

            return result;
        } catch (DataAccessException e) {
            log.error("traffic_lua_execute_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua ?ㅽ뻾???ㅽ뙣?덉뒿?덈떎.");
        }
    }

    /**
     * ?먯떆 ?낅젰??寃利앺븯怨??대? ?쒗쁽?쇰줈 蹂?섑빀?덈떎.
     */
    private TrafficLuaExecutionResult parseDeductResult(String rawJson, TrafficLuaScriptType scriptType) {
        // 諛섑솚媛믪씠 鍮꾩뼱 ?덉쑝硫?李④컧 寃곌낵瑜??좊ː?????놁쑝誘濡?利됱떆 ?ㅽ뙣 泥섎━?쒕떎.
        if (rawJson == null || rawJson.isBlank()) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua 李④컧 寃곌낵 ?뚯떛???ㅽ뙣?덉뒿?덈떎. script=" + scriptType.getScriptName()
            );
        }

        try {
            // ?ㅽ겕由쏀듃 JSON???꾩슜 DTO濡???쭅?ы솕??answer/status ????덉쟾?깆쓣 ?뺣낫?쒕떎.
            TrafficLuaDeductResDto parsedResult = objectMapper.readValue(rawJson, TrafficLuaDeductResDto.class);
            if (parsedResult.getStatus() == null) {
                throw new ApplicationException(
                        CommonErrorCode.INTERNAL_SERVER_ERROR,
                        "Lua ?곹깭 媛믪씠 鍮꾩뼱 ?덉뒿?덈떎. script=" + scriptType.getScriptName()
                );
            }

            return TrafficLuaExecutionResult.builder()
                    .answer(parsedResult.getAnswer())
                    .status(parsedResult.getStatus())
                    .build();
        } catch (JsonProcessingException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua JSON ?뚯떛???ㅽ뙣?덉뒿?덈떎. script=" + scriptType.getScriptName()
            );
        }
    }

    /**
      * `requireStringScript` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    private RedisScript<String> requireStringScript(TrafficLuaScriptType scriptType) {
        RedisScript<String> script = stringScriptRegistry.get(scriptType);
        if (script == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "?깅줉?섏? ?딆? Lua ?ㅽ겕由쏀듃?낅땲?? script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
      * `requireLongScript` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    private RedisScript<Long> requireLongScript(TrafficLuaScriptType scriptType) {
        RedisScript<Long> script = longScriptRegistry.get(scriptType);
        if (script == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "?깅줉?섏? ?딆? Lua ?ㅽ겕由쏀듃?낅땲?? script=" + scriptType.getScriptName()
            );
        }
        return script;
    }

    /**
      * `registerScript` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    private void registerScript(TrafficLuaScriptType scriptType, String scriptText) {
        // ?ㅽ겕由쏀듃 諛섑솚 怨꾩빟???곕씪 寃곌낵 ??낆쓣 遺꾨━ ?깅줉?쒕떎.
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
      * `preloadScriptSha` 泥섎━ 紐⑹쟻??留욌뒗 ?듭떖 濡쒖쭅???섑뻾?⑸땲??
     */
    private String preloadScriptSha(TrafficLuaScriptType scriptType, String scriptText) {
        try {
            // execute ?ㅻ쾭濡쒕뱶 紐⑦샇?깆쓣 ?쇳븯湲??꾪빐 RedisCallback ??낆쓣 紐낆떆?쒕떎.
            // scriptLoad??SHA1 臾몄옄?댁쓣 諛섑솚?섎?濡?洹몃?濡?蹂닿??쒕떎.
            String sha = cacheStringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.scriptingCommands().scriptLoad(scriptText.getBytes(StandardCharsets.UTF_8))
            );

            if (sha == null || sha.isBlank()) {
                throw new ApplicationException(
                        CommonErrorCode.EXTERNAL_SYSTEM_ERROR,
                        "Lua SHA preload 寃곌낵媛 鍮꾩뼱 ?덉뒿?덈떎. script=" + scriptType.getScriptName()
                );
            }

            scriptShaRegistry.put(scriptType, sha);
            return sha;
        } catch (DataAccessException e) {
            log.error("traffic_lua_preload_failed script={}", scriptType.getScriptName(), e);
            throw new ApplicationException(CommonErrorCode.EXTERNAL_SYSTEM_ERROR, "Lua SHA preload???ㅽ뙣?덉뒿?덈떎.");
        }
    }

    /**
     * ?꾩슂???먯쿇 ?곗씠?곕? 濡쒕뱶??諛섑솚?⑸땲??
     */
    private String loadScriptText(TrafficLuaScriptType scriptType) {
        ClassPathResource resource = new ClassPathResource(scriptType.getResourcePath());

        try {
            // classpath???깅줉??Lua ?먮Ц??臾몄옄?대줈 濡쒕뱶?쒕떎.
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Lua ?ㅽ겕由쏀듃 濡쒕뱶???ㅽ뙣?덉뒿?덈떎. script=" + scriptType.getScriptName()
            );
        }
    }

}
