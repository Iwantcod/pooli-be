package com.pooli.traffic.domain;

import java.util.Objects;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPolicyCheckFailureCause;
import lombok.Getter;

/**
 * Policy Check Layer 실행 결과 모델입니다. (차단성 정책 검증 결과)
 *
 * <p>이 타입은 불변 값 객체이며, 생성 경로를 정적 팩토리로 제한하기 위해
 * final class로 구현합니다.</p>
 */
@Getter
public final class TrafficPolicyCheckLayerResult {

    /**
     * 차단성 정책 검증 결과 상태입니다. (예: OK, BLOCKED_REPEAT, ERROR)
     */
    private final TrafficLuaStatus status;

    /**
     * 화이트리스트 우회 허용 여부입니다.
     * true면 차감 Lua 인자로 bypass=1을 전달합니다.
     */
    private final boolean whitelistBypass;

    /**
     * 차단성 정책 검증 단계에서 retry/reclaim 경로로 넘겨야 하는 인프라 실패인지 여부입니다.
     */
    private final boolean retryableFailure;

    /**
     * 차단성 정책 검증 단계 실패 원인 코드입니다.
     */
    private final TrafficPolicyCheckFailureCause failureCause;

    /**
     * retryableFailure=true인 경우의 원인 예외입니다.
     * retryableFailure=false인 경우 null이어야 합니다.
     */
    private final RuntimeException failure;

    /** 각 정책 검증 레이어의 실행 상태 및 최종 검증 결과를 생성하는 생성자입니다. */
    private TrafficPolicyCheckLayerResult(
            TrafficLuaStatus status,
            boolean whitelistBypass,
            boolean retryableFailure,
            TrafficPolicyCheckFailureCause failureCause,
            RuntimeException failure
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.whitelistBypass = whitelistBypass;
        this.retryableFailure = retryableFailure;
        this.failureCause = Objects.requireNonNull(failureCause, "failureCause");

        if (retryableFailure && failure == null) {
            throw new IllegalArgumentException("retryableFailure=true 인 경우 failure가 필요합니다.");
        }
        if (!retryableFailure && failure != null) {
            throw new IllegalArgumentException("retryableFailure=false 인 경우 failure는 null이어야 합니다.");
        }
        this.failure = failure;
    }

    /**
     * Lua 정책 검증 결과를 표준 결과 모델로 변환합니다.
     */
    public static TrafficPolicyCheckLayerResult fromLuaResult(TrafficLuaExecutionResult result) {
        if (result == null || result.getStatus() == null) {
            return new TrafficPolicyCheckLayerResult(
                    TrafficLuaStatus.ERROR,
                    false,
                    false,
                    TrafficPolicyCheckFailureCause.POLICY_CHECK_NON_RETRYABLE,
                    null
            );
        }

        boolean bypass = result.getStatus() == TrafficLuaStatus.OK && result.getAnswer() > 0;
        return new TrafficPolicyCheckLayerResult(
                result.getStatus(),
                bypass,
                false,
                TrafficPolicyCheckFailureCause.NONE,
                null
        );
    }

    /**
     * retryable 정책 검증 예외를 pending/reclaim 대상 실패 상태로 변환합니다.
     */
    public static TrafficPolicyCheckLayerResult retryableFailure(
            TrafficPolicyCheckFailureCause failureCause,
            RuntimeException failure
    ) {
        if (failureCause == TrafficPolicyCheckFailureCause.NONE) {
            throw new IllegalArgumentException("retryableFailure의 failureCause는 NONE일 수 없습니다.");
        }
        return new TrafficPolicyCheckLayerResult(
                TrafficLuaStatus.ERROR,
                false,
                true,
                failureCause,
                failure
        );
    }

    /**
     * 하위 단계에서 재사용하기 위한 Lua 결과 형식으로 변환합니다.
     */
    public TrafficLuaExecutionResult toLuaExecutionResult() {
        return TrafficLuaExecutionResult.builder()
                .answer(whitelistBypass ? 1L : 0L)
                .status(status)
                .build();
    }

    /**
     * Lua 인자 규격(0/1)에 맞는 whitelist 우회 플래그를 반환합니다.
     */
    public int getWhitelistBypass() {
        return whitelistBypass ? 1 : 0;
    }
}
