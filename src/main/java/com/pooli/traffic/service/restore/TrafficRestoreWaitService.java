package com.pooli.traffic.service.restore;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.common.config.AppStreamsProperties;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 시작 전 기존 Streams 처리 중 레코드가 빠져나갈 시간을 확보한다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreWaitService {

    private static final long RESTORE_BUFFER_MS = 1000L;

    private final AppStreamsProperties appStreamsProperties;

    /**
     * 최악 처리시간 설정값에 1초 buffer를 더한 시간만큼 대기한다.
     */
    public void waitWorstProcessingTimePlusBuffer() {
        long waitMs = Math.max(0L, appStreamsProperties.getReclaimWorstProcessingMs()) + RESTORE_BUFFER_MS;
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Redis 복구 시작 대기 중 interrupt가 발생했습니다.", e);
        }
    }
}
