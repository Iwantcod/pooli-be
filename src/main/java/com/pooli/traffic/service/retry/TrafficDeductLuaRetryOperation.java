package com.pooli.traffic.service.retry;

import com.pooli.traffic.domain.TrafficLuaExecutionResult;

/**
 * 차감 Lua 실행부를 retry 어댑터에 전달하기 위한 함수형 계약입니다.
 */
@FunctionalInterface
public interface TrafficDeductLuaRetryOperation {

    TrafficLuaExecutionResult execute();
}
