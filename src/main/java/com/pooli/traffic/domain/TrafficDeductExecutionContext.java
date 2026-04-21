package com.pooli.traffic.domain;

/**
 * 단일 traceId 처리 범위에서 Redis fallback 전환 여부를 보관하는 컨텍스트입니다.
 */
public class TrafficDeductExecutionContext {

    private final String traceId;
    private boolean redisFallbackActivated;
    private TrafficLuaExecutionResult blockingPolicyCheckResult;

    private TrafficDeductExecutionContext(String traceId) {
        this.traceId = traceId;
        this.redisFallbackActivated = false;
        this.blockingPolicyCheckResult = null;
    }

    /**
     * traceId 기반 컨텍스트를 생성합니다.
     */
    public static TrafficDeductExecutionContext of(String traceId) {
        return new TrafficDeductExecutionContext(traceId);
    }

    /**
     * 요청 범위를 Redis fallback 모드로 전환합니다.
     */
    public void activateRedisFallback() {
        this.redisFallbackActivated = true;
    }

    /**
     * 현재 요청이 Redis fallback 모드인지 반환합니다.
     */
    public boolean isRedisFallbackActivated() {
        return redisFallbackActivated;
    }

    /**
     * traceId 처리 범위에서 최초 1회 수행한 차단성 정책 검증 결과를 저장합니다.
     */
    public void cacheBlockingPolicyCheckResult(TrafficLuaExecutionResult result) {
        this.blockingPolicyCheckResult = result;
    }

    /**
     * 캐시된 차단성 정책 검증 결과를 반환합니다.
     */
    public TrafficLuaExecutionResult getBlockingPolicyCheckResult() {
        return blockingPolicyCheckResult;
    }

    /**
     * 차단성 정책 검증 결과가 이미 캐시되어 있는지 반환합니다.
     */
    public boolean hasBlockingPolicyCheckResult() {
        return blockingPolicyCheckResult != null;
    }

    /**
     * 컨텍스트 traceId를 반환합니다.
     */
    public String getTraceId() {
        return traceId;
    }
}
