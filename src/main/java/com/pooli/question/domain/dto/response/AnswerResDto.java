package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "답변 DTO")
public class AnswerResDto {
    @Schema(description = "답변 ID", example = "10")
    private Long answerId;

    @Schema(description = "관리자 ID", example = "100")
    private Long userId;

    @Schema(description = "답변 내용", example = "문의하신 요금제는 ~")
    private String content;

    @Schema(description = "답변 생성 시점", example = "2026-02-24T10:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "답변 첨부 파일 리스트")
    private List<AttachmentResDto> attachments;
}