package com.pooli.traffic.domain;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 차감 Lua 스크립트([answer, status]) 실행 결과를 표현하는 값 객체입니다.
 * 오케스트레이터가 tick 별 분기 결정을 할 때 사용합니다.
 */
@Getter
@Builder
@AllArgsConstructor
public class TrafficLuaExecutionResult {
    private final long answer;
    private final TrafficLuaStatus status;
}
