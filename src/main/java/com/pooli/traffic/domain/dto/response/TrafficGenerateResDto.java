package com.pooli.traffic.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 트래픽 발생 API가 Streams 적재 성공 시 반환하는 응답 DTO입니다.
 * traceId를 반환해 API 서버 로그와 traffic 서버 처리를 추적할 수 있도록 합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficGenerateResDto {

    @Schema(description = "요청 추적 ID", example = "3db88dc3-442d-489a-8e16-8de5054ec6a3")
    private String traceId;

    @Schema(description = "요청 enqueue 시각(epoch millis)", example = "1741602800000")
    private Long enqueuedAt;
}

