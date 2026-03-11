package com.pooli.traffic.domain.dto.response;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 차감 Lua 스크립트의 JSON 반환값(answer, status)을 역직렬화하기 위한 응답 DTO입니다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TrafficLuaDeductResDto {
    private long answer;
    private TrafficLuaStatus status;
}
