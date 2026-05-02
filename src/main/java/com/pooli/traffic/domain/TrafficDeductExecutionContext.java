package com.pooli.traffic.domain;

/**
 * 단일 traceId 처리 범위에서 공통 실행 상태를 보관하는 컨텍스트입니다.
 */
public class TrafficDeductExecutionContext {

    private final String traceId;
    private TrafficLuaExecutionResult blockingPolicyCheckResult;

    private TrafficDeductExecutionContext(String traceId) {
        this.traceId = traceId;
        this.blockingPolicyCheckResult = null;
    }

    /**
     * traceId 기반 컨텍스트를 생성합니다.
     */
    public static TrafficDeductExecutionContext of(String traceId) {
        return new TrafficDeductExecutionContext(traceId);
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
