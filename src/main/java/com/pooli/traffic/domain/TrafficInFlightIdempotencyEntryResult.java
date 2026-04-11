package com.pooli.traffic.domain;

/**
 * in-flight 멱등 hash 생성/조회 결과입니다.
 */
public record TrafficInFlightIdempotencyEntryResult(
        boolean created,
        TrafficInFlightIdempotencyEntry entry
) {
}
