package com.pooli.traffic.domain;

/**
 * traceId 단위 in-flight 멱등 hash 스냅샷입니다.
 *
 * @param key 중복 요청 방지를 위한 멱등성 키
 * @param processedIndividualData 처리 완료된 개인 데이터 차감량 (Byte 단위)
 * @param processedSharedData 처리 완료된 가족 공유 데이터 차감량 (Byte 단위)
 * @param processedQosData 처리 완료된 QoS 제어 대상 데이터 차감량 (Byte 단위)
 * @param retryCount 현재 요청의 재시도 횟수
 */
public record TrafficInFlightIdempotencyEntry(
        String key,
        long processedIndividualData,
        long processedSharedData,
        long processedQosData,
        int retryCount
) {

    /** 중복 요청 방지를 위한 In-Flight 멱등성 엔트리를 생성합니다. */
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
