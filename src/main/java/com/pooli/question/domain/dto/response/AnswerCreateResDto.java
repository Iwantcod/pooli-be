package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "문의사항 답변 생성 응답 DTO")
public class AnswerCreateResDto {

    @Schema(description = "답변 ID", example = "456")
    private Long answerId;
}