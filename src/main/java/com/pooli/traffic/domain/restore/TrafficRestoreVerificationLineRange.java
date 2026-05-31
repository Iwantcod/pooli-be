package com.pooli.traffic.domain.restore;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis 복구 검증 대상 원천 데이터에 등장한 line_id 범위이다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRestoreVerificationLineRange {

    /** 검증 대상 최소 line 식별자 */
    private Long minLineId;

    /** 검증 대상 최대 line 식별자 */
    private Long maxLineId;

    /**
     * 테스트와 명시적 객체 생성 경로에서 line_id 범위를 만든다.
     */
    public static TrafficRestoreVerificationLineRange of(Long minLineId, Long maxLineId) {
        return new TrafficRestoreVerificationLineRange(minLineId, maxLineId);
    }

    /**
     * 검증할 line 범위가 존재하는지 반환한다.
     */
    public boolean exists() {
        return minLineId != null && maxLineId != null;
    }
}
