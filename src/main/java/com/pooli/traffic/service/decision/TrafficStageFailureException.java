package com.pooli.traffic.service.decision;

/**
 * 단계별 Redis 인프라 실패를 표준화해 전파하기 위한 래퍼 예외입니다.
 *
 * <p>실제 retryable 판정은 기존 Redis 분류기(원인 cause chain 검사)가 담당하고,
 * 이 예외는 "어느 단계에서 실패했는지"를 로그/상위 처리에 전달하는 역할만 수행합니다.
 */
public class TrafficStageFailureException extends IllegalStateException {

    /** 예외가 발생한 파이프라인 단계입니다. */
    private final TrafficFailureStage stage;
    /** Redis 인프라 재시도 가능 예외인지 여부입니다. */
    private final boolean retryableInfrastructureFailure;

    /**
     * 단계별 실패 예외 인스턴스를 생성합니다.
     *
     * @param stage 실패가 발생한 단계
     * @param message 단계별 표준 로그/메시지 키
     * @param retryableInfrastructureFailure 재시도 가능 인프라 장애 여부
     * @param cause 원인 예외
     */
    private TrafficStageFailureException(
            TrafficFailureStage stage,
            String message,
            boolean retryableInfrastructureFailure,
            Throwable cause
    ) {
        super(message, cause);
        this.stage = stage;
        this.retryableInfrastructureFailure = retryableInfrastructureFailure;
    }

    /**
     * retryable Redis 인프라 장애를 단계 정보와 함께 래핑합니다.
     *
     * @param stage 실패 단계
     * @param cause 원인 예외
     * @return retryable=true로 고정된 단계 예외
     */
    public static TrafficStageFailureException retryableFailure(
            TrafficFailureStage stage,
            Throwable cause
    ) {
        return new TrafficStageFailureException(
                stage,
                stage.retryableFailureLogKey(),
                true,
                cause
        );
    }

    /**
     * non-retryable Redis 인프라 장애를 단계 정보와 함께 래핑합니다.
     *
     * @param stage 실패 단계
     * @param cause 원인 예외
     * @return retryable=false로 고정된 단계 예외
     */
    public static TrafficStageFailureException nonRetryableFailure(
            TrafficFailureStage stage,
            Throwable cause
    ) {
        return new TrafficStageFailureException(
                stage,
                stage.nonRetryableFailureLogKey(),
                false,
                cause
        );
    }

    /**
     * 재시도 소진 후 최종 실패를 단계 정보와 함께 래핑합니다.
     *
     * @param stage 실패 단계
     * @param cause 마지막 원인 예외(없으면 null 가능)
     * @return retryable=true이며 retry exhausted 메시지를 갖는 단계 예외
     */
    public static TrafficStageFailureException retryExhausted(
            TrafficFailureStage stage,
            Throwable cause
    ) {
        return new TrafficStageFailureException(
                stage,
                stage.retryExhaustedLogKey(),
                true,
                cause
        );
    }

    /**
     * 실패가 발생한 단계를 반환합니다.
     *
     * @return 단계 enum
     */
    public TrafficFailureStage getStage() {
        return stage;
    }

    /**
     * 재시도 가능 인프라 장애 여부를 반환합니다.
     *
     * @return true면 retryable failure/retry exhausted 계열
     */
    public boolean isRetryableInfrastructureFailure() {
        return retryableInfrastructureFailure;
    }
}
