package com.pooli.family.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "공유풀 데이터 담기 요청 DTO")
public class CreateSharedPoolContributionReqDto {

    @Schema(description = "가족 식별자", example = "1")
    private Integer familyId;

    @Schema(description = "회선 식별자", example = "10")
    private Integer lineId;

    @Schema(description = "공유풀에 담을 데이터량(Byte)", example = "500")
    private Long amount;
}