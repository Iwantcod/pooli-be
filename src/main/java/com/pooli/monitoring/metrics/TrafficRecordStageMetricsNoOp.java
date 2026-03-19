package com.pooli.monitoring.metrics;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local 외 프로파일에서 사용하는 no-op 메트릭 구현체입니다.
 * 동일 코드 경로를 유지하면서도 상세 계측 부하를 발생시키지 않습니다.
 */
@Component
@Profile("!local")
public class TrafficRecordStageMetricsNoOp implements TrafficRecordStageMetricsPort {

    @Override
    public void recordStageLatency(String stage, long durationMs) {
        // no-op
    }

    @Override
    public void recordTotalLatency(long durationMs) {
        // no-op
    }

    @Override
    public void incrementResult(String result) {
        // no-op
    }
}
