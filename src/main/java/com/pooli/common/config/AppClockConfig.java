package com.pooli.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션에서 공통으로 사용할 시간 기준을 제공한다.
 */
@Configuration
public class AppClockConfig {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 운영 배치와 사용량 정책에서 기준으로 삼는 KST clock을 Spring bean으로 제공한다.
     */
    @Bean
    public Clock appClock() {
        return Clock.system(APP_ZONE);
    }
}
