package com.pooli.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * M4-3-a: retry 인프라를 공통 활성화합니다.
 * 개별 @Retryable 적용은 후속 단계에서 확장합니다.
 */
@Configuration
@EnableRetry
public class TrafficRetryConfig {
}
