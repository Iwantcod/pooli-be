package com.pooli.question.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Schema(description = "문의사항 답변 생성 요청 DTO")
public class AnswerCreateReqDto {

    @NotNull(message = "questionId는 필수입니다")
    @Schema(description = "답변할 질문 ID", example = "123")
    private Long questionId;

    @NotBlank(message = "content는 필수입니다")
    @Schema(description = "답변 내용", example = "문의하신 요금제는 ~")
    private String content;

    @Valid
    @Schema(description = "첨부 파일 리스트")
    private List<AttachmentReqDto> attachments;
}