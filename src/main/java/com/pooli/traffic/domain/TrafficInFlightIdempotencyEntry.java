package com.pooli.traffic.domain;

/**
 * traceId 단위 in-flight 멱등 hash 스냅샷입니다.
 */
public record TrafficInFlightIdempotencyEntry(
        String key,
        long processedData,
        int retryCount
) {

    public static TrafficInFlightIdempotencyEntry of(
            String key,
            long processedData,
            int retryCount
    ) {
        long safeProcessedData = Math.max(0L, processedData);
        int safeRetryCount = Math.max(0, retryCount);
        return new TrafficInFlightIdempotencyEntry(key, safeProcessedData, safeRetryCount);
    }
}
