package com.pooli.traffic.domain;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Lua 스크립트([answer, status]) 실행 결과를 표현하는 값 객체입니다.
 *
 * <p>차감/정책검증 경로가 모두 이 타입을 공통으로 사용하므로,
 * answer의 의미는 호출 문맥(차감, 정책검증)에 따라 달라질 수 있습니다.
 * status는 항상 분기 기준 상태값입니다.
 */
@Getter
@Builder
@AllArgsConstructor
public class TrafficLuaExecutionResult {
    /**
     * Lua가 반환한 수치 응답입니다.
     *
     * <p>- 차감 Lua 경로: 처리/차감 결과 수치(현재 계약 기준)
     * <br>- 정책 검증 Lua 경로: 화이트리스트 우회 플래그(1=우회, 0=미우회)
     */
    private final long answer;

    /**
     * Lua 실행 결과 상태 코드입니다.
     *
     * <p>- 정책 검증 경로: OK, BLOCKED_IMMEDIATE, BLOCKED_REPEAT, GLOBAL_POLICY_HYDRATE, ERROR 등을 반환
     * <br>- 차감 경로: OK/NO_BALANCE/HYDRATE/QOS/ERROR 등을 반환
     */
    private final TrafficLuaStatus status;
}
