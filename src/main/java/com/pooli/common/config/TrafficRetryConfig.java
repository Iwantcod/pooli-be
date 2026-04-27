package com.pooli.common.config;

import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.EnableRetry;

/**
 * M4-3-a: retry 인프라를 traffic 프로파일에서만 활성화합니다.
 * 현재 단계에서는 공통 활성화 골격만 제공하며, 개별 @Retryable 적용은 후속 단계에서 수행합니다.
 */
@Profile("traffic")
@EnableRetry
public class TrafficRetryConfig {
}
