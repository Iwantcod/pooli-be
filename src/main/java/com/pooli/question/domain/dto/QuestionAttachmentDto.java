package com.pooli.question.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "첨부 파일 응답 DTO")
public class QuestionAttachmentDto {
	
	@Schema(description = "S3에 저장된 파일 경로(Key)", example = "questions/1/550e8400-e29b-41d4-a716-446655440000.png")
	private String s3Key;
	
	@Schema(description = "파일 크기", example = "204800")
    private int fileSize;
}
