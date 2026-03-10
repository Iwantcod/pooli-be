package com.pooli.traffic.domain.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * API 서버가 Streams로 발행하고 traffic 서버가 소비하는 요청 payload DTO입니다.
 * 명세의 필수 필드와 검증 규칙을 한곳에서 관리합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficPayloadReqDto {

    @NotBlank(message = "traceId는 필수입니다.")
    private String traceId;

    @NotNull(message = "lineId는 필수입니다.")
    @Positive(message = "lineId는 1 이상이어야 합니다.")
    private Long lineId;

    @NotNull(message = "familyId는 필수입니다.")
    @Positive(message = "familyId는 1 이상이어야 합니다.")
    private Long familyId;

    @NotNull(message = "appId는 필수입니다.")
    @Positive(message = "appId는 1 이상이어야 합니다.")
    private Integer appId;

    @NotNull(message = "apiTotalData는 필수입니다.")
    @Positive(message = "apiTotalData는 1 이상이어야 합니다.")
    private Long apiTotalData;

    @NotNull(message = "enqueuedAt은 필수입니다.")
    @Positive(message = "enqueuedAt은 epoch millis(양수)여야 합니다.")
    private Long enqueuedAt;
}
