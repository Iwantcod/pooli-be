package com.pooli.traffic.domain.dto.response;

import java.time.LocalDateTime;

import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * traffic 서버가 차감 처리를 완료한 뒤 영속 저장/응답 전달에 사용하는 결과 DTO입니다.
 * 최종 상태, 총 차감량, 마지막 Lua 상태를 함께 전달합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficDeductResultResDto {
    private String traceId;
    private Long apiTotalData;
    private Long deductedTotalBytes;
    private Long apiRemainingData;
    private TrafficFinalStatus finalStatus;
    private TrafficLuaStatus lastLuaStatus;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
