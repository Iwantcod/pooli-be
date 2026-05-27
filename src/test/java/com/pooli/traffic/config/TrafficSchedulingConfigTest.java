package com.pooli.traffic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class TrafficSchedulingConfigTest {

    private static final String APPLICATION_YAML = "src/main/resources/application.yaml";

    @Test
    @DisplayName("Spring TaskScheduler pool size는 2로 설정한다")
    void taskSchedulerPoolSizeIsTwo() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            List<PropertySource<?>> propertySources = loader.load("application", new FileSystemResource(APPLICATION_YAML));
            StandardEnvironment env = new StandardEnvironment();
            propertySources.forEach(env.getPropertySources()::addFirst);

            int poolSize = env.getProperty("spring.task.scheduling.pool.size", Integer.class, -1);
            assertEquals(2, poolSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
