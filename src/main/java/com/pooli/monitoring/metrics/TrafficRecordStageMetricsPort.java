package com.pooli.monitoring.metrics;

/**
 * Streams 레코드 처리(handleRecord) 단계별 메트릭 기록을 추상화한 포트입니다.
 * 소비자 러너는 이 인터페이스만 의존해 profile별(local/no-op) 구현을 분리합니다.
 */
public interface TrafficRecordStageMetricsPort {

    /**
     * handleRecord 내부 특정 단계의 처리 시간을 기록합니다.
     */
    void recordStageLatency(String stage, long durationMs);

    /**
     * handleRecord 전체 처리 시간을 기록합니다.
     */
    void recordTotalLatency(long durationMs);

    /**
     * handleRecord 처리 결과 카운터를 증가시킵니다.
     */
    void incrementResult(String result);
}
