package com.pooli.traffic.domain;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 */
@Getter
@Builder
@AllArgsConstructor
public class TrafficLuaExecutionResult {
    private final long answer;
    private final TrafficLuaStatus status;
}
