package com.pooli.traffic.util;

/**
 * 트래픽 도메인 전역 재시도 정책에서 사용하는 지수 백오프 계산 유틸입니다.
 */
public final class TrafficRetryBackoffSupport {

    private TrafficRetryBackoffSupport() {
    }

    /**
     * retryAttempt(1..N)에 대해 base * 2^(retryAttempt-1) 지연 시간을 계산합니다.
     */
    public static long resolveDelayMs(long baseBackoffMs, int retryAttempt) {
        // 음수/비정상 base는 0으로 보정해 대기 없이 즉시 다음 로직으로 진행합니다.
        long safeBase = Math.max(0L, baseBackoffMs);
        if (safeBase <= 0L) {
            return 0L;
        }

        // retryAttempt=1이면 shift=0(=base), retryAttempt=2이면 shift=1(=2*base) 형태입니다.
        long shift = Math.max(0L, (long) retryAttempt - 1L);
        // long 비트폭을 초과하는 시프트는 의미가 없으므로 최대값으로 포화(saturating) 처리합니다.
        if (shift >= 63L) {
            return Long.MAX_VALUE;
        }

        // 2^(retryAttempt-1) 배수를 계산합니다.
        long multiplier = 1L << shift;
        // safeBase * multiplier 곱셈에서 overflow가 나지 않도록 사전 방어합니다.
        if (safeBase > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        // 정상 범위에서는 지수 백오프 지연 시간을 그대로 반환합니다.
        return safeBase * multiplier;
    }
}
