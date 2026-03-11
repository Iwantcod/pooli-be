package com.pooli.traffic.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 실시간 시스템 시계(System.nanoTime) 기준으로 tick 시작 시점을 맞추는 기본 구현입니다.
 * 목표는 "tick 1개/초"를 보장하는 것이며, 처리 지연이 발생하면 다음 tick을 즉시 진행해
 * 지연 누적(lag)만 측정하도록 설계합니다.
 */
@Service
@Profile({"local", "traffic"})
public class TrafficSystemTickPacer implements TrafficTickPacer {

    private final long tickIntervalNanos;

    public TrafficSystemTickPacer(@Value("${app.traffic.tick-interval-ms:1000}") long tickIntervalMs) {
        // 잘못된 설정(0 이하)을 방어하기 위해 기본값 1000ms를 강제합니다.
        long normalizedIntervalMs = tickIntervalMs > 0 ? tickIntervalMs : 1000L;
        this.tickIntervalNanos = TimeUnit.MILLISECONDS.toNanos(normalizedIntervalMs);
    }

    @Override
    public long awaitTickStart(long orchestrationStartNano, int tickNumber) {
        if (orchestrationStartNano <= 0 || tickNumber <= 0) {
            return 0L;
        }

        long tickOffsetNanos = tickIntervalNanos * (tickNumber - 1L);
        long scheduledStartNano = orchestrationStartNano + tickOffsetNanos;

        // 아직 tick 시작 시각 전이면 남은 시간만큼 대기합니다.
        // 중간에 인터럽트가 걸리면 대기를 중단하고 호출 측이 상태를 처리할 수 있게 합니다.
        while (true) {
            long remainingNanos = scheduledStartNano - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }

            LockSupport.parkNanos(remainingNanos);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 처리 지연으로 시작 시각이 이미 지난 경우 lag(ms)만 반환합니다.
        long lagNanos = System.nanoTime() - scheduledStartNano;
        if (lagNanos <= 0) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(lagNanos);
    }
}
