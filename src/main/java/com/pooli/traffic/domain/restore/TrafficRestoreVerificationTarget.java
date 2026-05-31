package com.pooli.traffic.domain.restore;

import java.time.LocalDate;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis string value 또는 hash field 하나에 대한 복구 검증 기준값이다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRestoreVerificationTarget {

    /** Redis key 생성 규칙을 결정하는 검증 대상 유형 */
    private TrafficRestoreVerificationKeyType keyType;

    /** 회선 단위 key를 만들 때 사용하는 line 식별자 */
    private Long lineId;

    /** 공유풀 잔량 key를 만들 때 사용하는 family 식별자 */
    private Long familyId;

    /** 전역 정책 key를 만들 때 사용하는 policy 식별자 */
    private Long policyId;

    /** 일별 사용량 key를 만들 때 사용하는 업무일 */
    private LocalDate usageDate;

    /** 월별 잔량/사용량 key를 만들 때 사용하는 대상 월의 1일 */
    private LocalDate monthStart;

    /** 앱별 사용량 field 산출 근거가 되는 application 식별자 */
    private Integer applicationId;

    /** Redis hash field 이름 또는 string value sentinel */
    private String field;

    /** Redis string value 또는 hash field가 가져야 하는 기준값 */
    private Long expectedValue;

    /** 보정 후 적용할 Redis key 만료 epoch seconds */
    private Long expireEpochSeconds;
}
