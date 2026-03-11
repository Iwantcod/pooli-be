package com.pooli.traffic.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * traffic/local 프로파일에서만 스케줄링을 활성화합니다.
 */
@Configuration
@EnableScheduling
@Profile({"local", "traffic"})
public class TrafficSchedulingConfig {
}
