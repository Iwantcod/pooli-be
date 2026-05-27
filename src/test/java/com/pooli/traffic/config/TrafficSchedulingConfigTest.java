package com.pooli.traffic.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficSchedulingConfigTest {

    private static final String APPLICATION_YAML = "src/main/resources/application.yaml";

    @Test
    @DisplayName("Spring TaskScheduler pool size는 2로 설정한다")
    void taskSchedulerPoolSizeIsTwo() {
        String yaml = read(APPLICATION_YAML);

        assertTrue(yaml.contains("task:\n    scheduling:\n      pool:\n        size: 2"));
    }

    private String read(String path) {
        try {
            return Files.readString(
                    Path.of(path),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
