package com.pooli.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * cache Redis 연결 정보와 AOF 백업 정책을 바인딩합니다.
 * traffic 정책/차감/멱등 키가 cache Redis에 저장되므로 AOF도 이 설정 아래에서 함께 관리합니다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.redis.cache")
public class CacheRedisProperties {
    private String host;
    private int port;
    private String password;
    /**
     * Redis TCP connect timeout(ms). 0 이하면 드라이버 기본값을 사용합니다.
     */
    private long connectTimeoutMs;
    /**
     * Redis command timeout(ms). 0 이하면 드라이버 기본값을 사용합니다.
     */
    private long commandTimeoutMs;
    private SentinelProperties sentinel = new SentinelProperties();
    private AofProperties aof = new AofProperties();

    @Getter
    @Setter
    public static class SentinelProperties {
        private String master;
        private String nodes;
        private String password;
    }

    /**
     * cache Redis AOF 설정입니다.
     * 운영 환경별 제약이 다르므로 "앱이 직접 설정할지", "이미 설정된 값을 검증만 할지"를 분리했습니다.
     */
    @Getter
    @Setter
    public static class AofProperties {
        /** AOF 백업 기능 전체 on/off */
        private boolean enabled;
        /** 부팅 시 CONFIG SET으로 Redis 서버 설정을 직접 맞출지 여부 */
        private boolean configureOnStartup = true;
        /** 기대한 AOF 설정이 아니면 애플리케이션 기동을 중단할지 여부 */
        private boolean failFast = true;
        /** 쓰기 성능과 유실 범위의 균형점으로 everysec를 기본값으로 사용 */
        private String appendfsync = "everysec";
        /** AOF 파일이 얼마나 커졌을 때 rewrite를 시작할지 정하는 비율 */
        private int autoRewritePercentage = 100;
        /** 너무 작은 파일에서 rewrite가 자주 돌지 않도록 최소 크기를 둠 */
        private String autoRewriteMinSize = "64mb";
        /** 재시작 속도를 위해 RDB preamble 사용 여부를 함께 관리 */
        private boolean useRdbPreamble = true;
        /** CONFIG SET 후 redis.conf에 반영 가능한 환경이면 즉시 rewrite */
        private boolean rewriteConfigOnStartup = true;
        /** 운영자가 원할 때만 초기 rewrite를 강제로 트리거 */
        private boolean triggerBackgroundRewriteOnStartup;
    }
}
