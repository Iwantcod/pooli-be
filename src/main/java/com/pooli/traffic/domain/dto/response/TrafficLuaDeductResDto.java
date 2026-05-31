package com.pooli.traffic.domain.dto.response;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lua 스크립트의 JSON 반환값을 역직렬화하기 위한 응답 DTO입니다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TrafficLuaDeductResDto {
    /**
     * 기존 단일 수치 Lua(block policy, legacy 개인/공유 차감)의 answer/status 응답을 파싱하기 위한 하위 호환 필드입니다.
     */
    private long answer;
    /** 개인 풀 차감 바이트 수 */
    private long indivDeducted;
    /** 가족 공유 풀 차감 바이트 수 */
    private long sharedDeducted;
    /** QoS 적용 차감 바이트 수 */
    private long qosDeducted;
    /** 스크립트 처리 완료 시각 */
    private Long finishedAtEpochMillis;
    /** 실행 결과 상태 코드 */
    private TrafficLuaStatus status;
}
