package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "문의 카테고리 응답 DTO")
public class QuestionCategoryResDto {

    @Schema(description = "문의 카테고리 ID", example = "1")
    private Integer questionCategoryId;

    @Schema(description = "문의 카테고리 이름", example = "요금제 문의")
    private String questionCategoryName;


}
