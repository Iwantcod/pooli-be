package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "문의 카테고리 응답 DTO")
public class QuestionCreateResDto {
    @Schema(description = "문의사항 식별자", example = "2")
    private Long questionId;

    @Schema(description = "문의사항 제목", example = "요금제 관련 문의드립니다")
    private String title;
}