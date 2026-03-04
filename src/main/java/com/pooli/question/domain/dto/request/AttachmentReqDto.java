package com.pooli.question.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Schema(description = "첨부 파일 응답 DTO")
public class AttachmentReqDto {

	@NotBlank(message = "S3 Key는 필수입니다.")
	@Schema(description = "S3에 저장된 파일 경로(Key)", example = "questions/1/550e8400-e29b-41d4-a716-446655440000.png")
	private String s3Key;

	@NotNull(message = "파일 크기는 필수입니다.")
	@Schema(description = "파일 크기", example = "204800")
    private Integer fileSize;
}
