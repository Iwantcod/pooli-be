package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "문의 카테고리 목록 응답 DTO")
public class QuestionCategoryListResDto {

    @ArraySchema(schema = @Schema(implementation = QuestionCategoryResDto.class))
    private List<QuestionCategoryResDto> questionCategories;
}
