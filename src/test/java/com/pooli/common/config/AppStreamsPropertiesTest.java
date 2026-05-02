package com.pooli.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.traffic.service.invoke.TrafficStreamBootstrapException;

class AppStreamsPropertiesTest {

    @Test
    @DisplayName("reclaim worst processing 값이 없으면 reclaim-min-idle 설정값을 그대로 사용한다")
    void usesConfiguredReclaimMinIdleWhenWorstProcessingIsNotSet() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setReclaimMinIdleMs(15_000L);
        properties.setReclaimWorstProcessingMs(0L);

        long reclaimMinIdleMs = properties.resolveReclaimMinIdleMs();

        assertEquals(15_000L, reclaimMinIdleMs);
    }

    @Test
    @DisplayName("reclaim min-idle을 최악 처리시간의 1.5배로 계산한다")
    void calculatesReclaimMinIdleFromWorstProcessingTime() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setReclaimMinIdleMs(1_000L);
        properties.setReclaimWorstProcessingMs(10_000L);

        long reclaimMinIdleMs = properties.resolveReclaimMinIdleMs();

        assertEquals(15_000L, reclaimMinIdleMs);
    }

    @Test
    @DisplayName("reclaim min-idle 계산 시 소수점은 올림 처리한다")
    void roundsUpWhenCalculatingReclaimMinIdle() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setReclaimMinIdleMs(1_000L);
        properties.setReclaimWorstProcessingMs(10_001L);

        long reclaimMinIdleMs = properties.resolveReclaimMinIdleMs();

        assertEquals(15_002L, reclaimMinIdleMs);
    }

    @Test
    @DisplayName("reclaim-min-idle이 음수면 예외를 던진다")
    void throwsWhenReclaimMinIdleIsNegative() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setReclaimMinIdleMs(-1L);

        assertThrows(TrafficStreamBootstrapException.class, properties::resolveReclaimMinIdleMs);
    }
}
