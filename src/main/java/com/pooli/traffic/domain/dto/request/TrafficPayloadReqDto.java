package com.pooli.traffic.domain.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * API 서버가 Streams로 발행하고 traffic 서버가 소비하는 요청 payload DTO입니다.
 * 명세의 필수 데이터 스키마를 한곳에서 관리합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficPayloadReqDto {

    private String traceId;

    private Long lineId;

    private Long familyId;

    private Integer appId;

    private Long apiTotalData;

    private Long enqueuedAt;
}
