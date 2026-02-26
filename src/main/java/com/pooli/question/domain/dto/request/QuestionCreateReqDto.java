package com.pooli.question.domain.dto.request;

import java.util.List;

import com.pooli.question.domain.dto.QuestionAttachmentDto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "문의사항 생성 요청 DTO")
public class QuestionCreateReqDto {

	@NotNull(message = "문의 카테고리는 필수입니다.")
	@Schema(description = "문의사항 카테고리 ID", example = "2")
	private Integer questionCategoryId;

	@NotNull(message = "회선 ID는 필수입니다.")
	@Schema(description = "회선 ID", example = "3")
	private Long lineId;

	@NotBlank(message = "제목은 비어 있을 수 없습니다.")
	@Schema(description = "문의사항 제목", example = "요금제 관련 문의드립니다")
	private String title;

	@NotBlank(message = "내용은 비어 있을 수 없습니다.")
	@Schema(description = "문의사항 내용", example = "가격이 더 비싸진 것 같아요")
	private String content;

	@Valid
	@Size(max = 3, message = "파일은 최대 3개까지 가능합니다.")
	@Schema(description = "문의사항 첨부 파일 목록")
	private List<QuestionAttachmentDto> attachments;
}
