package com.pooli.traffic.domain;

/**
 * traceId 단위 in-flight 멱등 hash 스냅샷입니다.
 */
public record TrafficInFlightIdempotencyEntry(
        String key,
        long processedIndividualData,
        long processedSharedData,
        long processedQosData,
        int retryCount
) {

    public static TrafficInFlightIdempotencyEntry of(
            String key,
            long processedIndividualData,
            long processedSharedData,
            long processedQosData,
            int retryCount
    ) {
        long safeProcessedIndividualData = Math.max(0L, processedIndividualData);
        long safeProcessedSharedData = Math.max(0L, processedSharedData);
        long safeProcessedQosData = Math.max(0L, processedQosData);
        int safeRetryCount = Math.max(0, retryCount);
        return new TrafficInFlightIdempotencyEntry(
                key,
                safeProcessedIndividualData,
                safeProcessedSharedData,
                safeProcessedQosData,
                safeRetryCount
        );
    }
}
