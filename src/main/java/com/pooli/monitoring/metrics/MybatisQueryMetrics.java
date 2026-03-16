package com.pooli.monitoring.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * MyBatis 쿼리 실행 시간을 Micrometer Timer로 기록한다.
 *
 * 태그:
 *   - mapper: Mapper 인터페이스의 단순 클래스명 (예: UserMapper)
 *   - operation: 메서드명 (예: selectById)
 *
 * raw SQL은 태그에 절대 넣지 않는다 (카디널리티 폭발 방지).
 */
@Component
public class MybatisQueryMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public MybatisQueryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String mapper, String operation, long durationMs) {
        String key = mapper + "." + operation;
        Timer timer = timers.computeIfAbsent(key, k ->
                Timer.builder("mybatis_query_duration_seconds")
                        .description("MyBatis query execution time")
                        .tag("mapper", mapper)
                        .tag("operation", operation)
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
        );
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}
