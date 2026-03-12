package com.pooli.data.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "데이터 이체 요청 DTO")
public class DataTransferReqDto {

    @Schema(description = "출발 회선 ID", example = "1")
    private Integer fromLineId;

    @Schema(description = "도착 회선 ID", example = "2")
    private Integer toLineId;

    @Schema(description = "이체 데이터 용량(Byte)", example = "1073741824")
    private Long amount;
}
