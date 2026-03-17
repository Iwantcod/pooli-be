package com.pooli.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * cache Redis의 AOF 설정을 부팅 시점에 강제하거나 검증합니다.
 * AOF는 키 단위가 아니라 인스턴스 단위 영속화이므로,
 * traffic 정책/차감/멱등 키가 올라가는 cache Redis 전체를 백업 경계로 봅니다.
 */
@Slf4j
@RequiredArgsConstructor
public class CacheRedisAofBackupService {

    private static final String YES = "yes";
    private static final String NO = "no";

    private final StringRedisTemplate cacheStringRedisTemplate;
    private final CacheRedisProperties cacheRedisProperties;

    /**
     * AOF 활성화 여부에 따라
     * 1) 설정 적용
     * 2) 실제 값 검증
     * 3) 필요 시 초기 rewrite
     * 순서로 수행합니다.
     */
    public void ensureAofReady() {
        CacheRedisProperties.AofProperties aof = cacheRedisProperties.getAof();
        // 환경마다 AOF 운영 정책이 다르므로 비활성화된 경우는 아무 것도 하지 않습니다.
        if (!aof.isEnabled()) {
            log.info("cache_redis_aof_backup_disabled");
            return;
        }

        try {
            validateRequestedSettings(aof);

            if (aof.isConfigureOnStartup()) {
                // 앱이 Redis 설정 권한을 가진 환경에서는 기동 시점에 기대값으로 맞춥니다.
                applyConfiguration(aof);
                if (aof.isRewriteConfigOnStartup()) {
                    // redis.conf까지 반영 가능한 환경이면 재기동 후에도 설정이 유지되도록 저장합니다.
                    rewriteConfig();
                }
            }

            // CONFIG SET 이후에도 실제 서버 값이 기대와 다를 수 있으므로 항상 한 번 더 검증합니다.
            AofState actualState = readCurrentState();
            List<String> mismatches = validateActualState(aof, actualState);
            if (!mismatches.isEmpty()) {
                handleValidationFailure(aof, mismatches);
                return;
            }

            if (aof.isTriggerBackgroundRewriteOnStartup()) {
                // 대용량 운영 환경에서는 비용이 있으므로 옵션으로만 실행합니다.
                triggerBackgroundRewrite();
            }

            log.info(
                    "cache_redis_aof_backup_ready appendonly={} appendfsync={} autoRewritePercentage={} autoRewriteMinSize={} useRdbPreamble={}",
                    actualState.appendonly(),
                    actualState.appendfsync(),
                    actualState.autoRewritePercentage(),
                    actualState.autoRewriteMinSize(),
                    actualState.useRdbPreamble()
            );
        } catch (RuntimeException e) {
            if (aof.isFailFast()) {
                // 정책/차감 키를 AOF 없이 쓰기 시작하면 복구 경계가 흐려지므로 즉시 실패시킵니다.
                throw new IllegalStateException("Failed to enforce cache Redis AOF backup settings.", e);
            }
            // 관리형 Redis처럼 설정 변경이 제한된 환경에서는 경고만 남기고 진행할 수 있게 둡니다.
            log.warn("cache_redis_aof_backup_setup_failed failFast=false", e);
        }
    }

    /**
     * Redis 서버 설정을 AOF 기준값으로 맞춥니다.
     */
    private void applyConfiguration(CacheRedisProperties.AofProperties aof) {
        String normalizedAppendfsync = normalizeAppendfsync(aof.getAppendfsync());
        String normalizedMinSize = normalizeSizeLiteral(aof.getAutoRewriteMinSize());
        String useRdbPreamble = booleanLiteral(aof.isUseRdbPreamble());

        execute(connection -> {
            // 최신 Spring Data Redis에서는 serverCommands() 경유가 권장됩니다.
            connection.serverCommands().setConfig("appendonly", YES);
            connection.serverCommands().setConfig("appendfsync", normalizedAppendfsync);
            connection.serverCommands().setConfig("auto-aof-rewrite-percentage", String.valueOf(aof.getAutoRewritePercentage()));
            connection.serverCommands().setConfig("auto-aof-rewrite-min-size", normalizedMinSize);
            connection.serverCommands().setConfig("aof-use-rdb-preamble", useRdbPreamble);
            return null;
        });

        log.info(
                "cache_redis_aof_backup_config_applied appendfsync={} autoRewritePercentage={} autoRewriteMinSize={} useRdbPreamble={}",
                normalizedAppendfsync,
                aof.getAutoRewritePercentage(),
                normalizedMinSize,
                useRdbPreamble
        );
    }

    /**
     * CONFIG SET 값이 재시작 후에도 유지되도록 redis.conf 재작성 명령을 보냅니다.
     */
    private void rewriteConfig() {
        execute(connection -> {
            connection.serverCommands().rewriteConfig();
            return null;
        });
        log.info("cache_redis_aof_backup_config_rewritten");
    }

    /**
     * 운영자가 원할 경우에만 AOF 파일 재작성 작업을 즉시 시작합니다.
     */
    private void triggerBackgroundRewrite() {
        execute(connection -> {
            connection.serverCommands().bgReWriteAof();
            return null;
        });
        log.info("cache_redis_aof_backup_bgrewrite_triggered");
    }

    /**
     * Redis 서버가 실제로 어떤 AOF 설정값으로 동작 중인지 읽어옵니다.
     */
    private AofState readCurrentState() {
        Properties properties = execute(connection -> {
            Properties merged = new Properties();
            mergeConfig(merged, connection.serverCommands().getConfig("appendonly"));
            mergeConfig(merged, connection.serverCommands().getConfig("appendfsync"));
            mergeConfig(merged, connection.serverCommands().getConfig("auto-aof-rewrite-percentage"));
            mergeConfig(merged, connection.serverCommands().getConfig("auto-aof-rewrite-min-size"));
            mergeConfig(merged, connection.serverCommands().getConfig("aof-use-rdb-preamble"));
            return merged;
        });

        if (properties == null || properties.isEmpty()) {
            throw new IllegalStateException("Cache Redis CONFIG GET returned no AOF settings.");
        }

        return new AofState(
                readProperty(properties, "appendonly"),
                readProperty(properties, "appendfsync"),
                readProperty(properties, "auto-aof-rewrite-percentage"),
                readProperty(properties, "auto-aof-rewrite-min-size"),
                readProperty(properties, "aof-use-rdb-preamble")
        );
    }

    /**
     * 설정 파일에 적힌 기대값과 실제 Redis 서버 값을 비교합니다.
     * auto-aof-rewrite-min-size는 64mb / 67108864처럼 표현이 달라도 같은 값으로 처리합니다.
     */
    private List<String> validateActualState(CacheRedisProperties.AofProperties aof, AofState actualState) {
        List<String> mismatches = new ArrayList<>();

        compareLiteral(mismatches, "appendonly", YES, actualState.appendonly());
        compareLiteral(mismatches, "appendfsync", normalizeAppendfsync(aof.getAppendfsync()), actualState.appendfsync());
        compareInteger(
                mismatches,
                "auto-aof-rewrite-percentage",
                aof.getAutoRewritePercentage(),
                actualState.autoRewritePercentage()
        );
        compareSizeBytes(
                mismatches,
                "auto-aof-rewrite-min-size",
                aof.getAutoRewriteMinSize(),
                actualState.autoRewriteMinSize()
        );
        compareLiteral(
                mismatches,
                "aof-use-rdb-preamble",
                booleanLiteral(aof.isUseRdbPreamble()),
                actualState.useRdbPreamble()
        );

        return mismatches;
    }

    /**
     * 운영 정책에 따라 검증 실패를 즉시 오류로 올리거나 경고 로그만 남깁니다.
     */
    private void handleValidationFailure(CacheRedisProperties.AofProperties aof, List<String> mismatches) {
        String message = "Cache Redis AOF validation failed: " + String.join("; ", mismatches);
        if (aof.isFailFast()) {
            throw new IllegalStateException(message);
        }
        log.warn("cache_redis_aof_backup_validation_failed failFast=false details={}", message);
    }

    /**
     * 잘못된 설정값이 들어오면 Redis 호출 전에 애플리케이션에서 먼저 막습니다.
     */
    private void validateRequestedSettings(CacheRedisProperties.AofProperties aof) {
        normalizeAppendfsync(aof.getAppendfsync());
        if (aof.getAutoRewritePercentage() < 0) {
            throw new IllegalArgumentException("auto-aof-rewrite-percentage must be greater than or equal to 0.");
        }
        parseSizeToBytes(aof.getAutoRewriteMinSize(), "auto-aof-rewrite-min-size");
    }

    private void compareLiteral(List<String> mismatches, String configName, String expected, String actual) {
        String normalizedExpected = normalizeLiteral(expected);
        String normalizedActual = normalizeLiteral(actual);
        if (!normalizedExpected.equals(normalizedActual)) {
            mismatches.add(configName + " expected=" + normalizedExpected + " actual=" + normalizedActual);
        }
    }

    private void compareInteger(List<String> mismatches, String configName, int expected, String actualRaw) {
        try {
            int actual = Integer.parseInt(normalizeLiteral(actualRaw));
            if (expected != actual) {
                mismatches.add(configName + " expected=" + expected + " actual=" + actual);
            }
        } catch (NumberFormatException e) {
            mismatches.add(configName + " expected=" + expected + " actual=" + normalizeLiteral(actualRaw));
        }
    }

    private void compareSizeBytes(List<String> mismatches, String configName, String expectedRaw, String actualRaw) {
        try {
            long expectedBytes = parseSizeToBytes(expectedRaw, configName);
            long actualBytes = parseSizeToBytes(actualRaw, configName);
            if (expectedBytes != actualBytes) {
                mismatches.add(configName + " expected=" + expectedBytes + "B actual=" + actualBytes + "B");
            }
        } catch (IllegalArgumentException e) {
            mismatches.add(configName + " expected=" + normalizeLiteral(expectedRaw) + " actual=" + normalizeLiteral(actualRaw));
        }
    }

    private void mergeConfig(Properties target, Properties source) {
        if (source != null) {
            target.putAll(source);
        }
    }

    private String readProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "<missing>" : value;
    }

    private String booleanLiteral(boolean value) {
        return value ? YES : NO;
    }

    private String normalizeAppendfsync(String rawValue) {
        String normalized = normalizeLiteral(rawValue);
        if (!"always".equals(normalized) && !"everysec".equals(normalized) && !"no".equals(normalized)) {
            throw new IllegalArgumentException(
                    "appendfsync must be one of always, everysec, no. actual=" + normalized
            );
        }
        return normalized;
    }

    private String normalizeSizeLiteral(String rawValue) {
        String normalized = normalizeLiteral(rawValue).replace("_", "");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("auto-aof-rewrite-min-size must not be blank.");
        }
        return normalized;
    }

    /**
     * Redis가 size literal과 byte 숫자를 모두 반환할 수 있으므로
     * 비교 전에 공통 바이트 값으로 정규화합니다.
     */
    private long parseSizeToBytes(String rawValue, String configName) {
        String normalized = normalizeSizeLiteral(rawValue).replace(" ", "");
        long multiplier = 1L;

        if (normalized.endsWith("kb")) {
            multiplier = 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("mb")) {
            multiplier = 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("gb")) {
            multiplier = 1024L * 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("tb")) {
            multiplier = 1024L * 1024L * 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        }

        try {
            return Long.parseLong(normalized) * multiplier;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(configName + " must be a number or size literal. actual=" + rawValue, e);
        }
    }

    private String normalizeLiteral(String rawValue) {
        if (rawValue == null) {
            return "<missing>";
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private <T> T execute(RedisCallback<T> callback) {
        return cacheStringRedisTemplate.execute(callback);
    }

    /**
     * 실제 Redis 서버에서 읽은 AOF 설정 스냅샷입니다.
     */
    private record AofState(
            String appendonly,
            String appendfsync,
            String autoRewritePercentage,
            String autoRewriteMinSize,
            String useRdbPreamble
    ) {
    }
}
