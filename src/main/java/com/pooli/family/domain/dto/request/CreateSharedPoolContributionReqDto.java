package com.pooli.family.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "공유풀 데이터 담기 요청 DTO")
public class CreateSharedPoolContributionReqDto {

    @Schema(description = "공유풀에 담을 데이터량(Byte)", example = "500000000")
    private Long amount;
}