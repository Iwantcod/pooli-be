package com.pooli.traffic.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 트래픽 발생 API가 클라이언트로부터 수신하는 요청 DTO입니다.
 * API 서버는 본 DTO를 기반으로 traceId/enqueuedAt을 보강해 Streams payload를 생성합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficGenerateReqDto {

    @Schema(description = "회선 ID", example = "1001")
    private Long lineId;

    @Schema(description = "가족 ID", example = "77")
    private Long familyId;

    @Schema(description = "애플리케이션 ID", example = "12")
    private Integer appId;

    @Schema(description = "향후 10초 총 데이터량(Byte)", example = "1048576")
    private Long apiTotalData;
}
