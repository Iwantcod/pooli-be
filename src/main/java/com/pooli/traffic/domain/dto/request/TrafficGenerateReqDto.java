package com.pooli.traffic.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
    @NotNull(message = "lineId는 필수입니다.")
    @Positive(message = "lineId는 1 이상이어야 합니다.")
    private Long lineId;

    @Schema(description = "가족 ID", example = "77")
    @NotNull(message = "familyId는 필수입니다.")
    @Positive(message = "familyId는 1 이상이어야 합니다.")
    private Long familyId;

    @Schema(description = "애플리케이션 ID", example = "12")
    @NotNull(message = "appId는 필수입니다.")
    @Positive(message = "appId는 1 이상이어야 합니다.")
    private Integer appId;

    @Schema(description = "요청 이벤트 데이터량(Byte)", example = "1048576")
    @NotNull(message = "apiTotalData는 필수입니다.")
    @PositiveOrZero(message = "apiTotalData는 0 이상이어야 합니다.")
    private Long apiTotalData;
}
