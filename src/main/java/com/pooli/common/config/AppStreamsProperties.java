package com.pooli.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Streams 소비/적재와 관련된 애플리케이션 설정값을 바인딩합니다.
 * 3단계에서는 producer가 사용하는 stream key를 우선 활용하고,
 * 이후 consumer/reclaim 단계에서 나머지 필드를 재사용합니다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.streams")
public class AppStreamsProperties {
    private String keyTrafficRequest;
    private String groupTraffic;
    private String consumerName;
    private int readCount;
    private long blockMs;
    private long reclaimIntervalMs;
    private long reclaimMinIdleMs;
    private int maxRetry;
    private String keyTrafficDlq;
}

