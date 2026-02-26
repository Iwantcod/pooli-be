package com.pooli.question.domain.dto.request;

import java.util.List;

import com.pooli.question.domain.dto.QuestionAttachmentDto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "문의사항 생성 요청 DTO")
public class QuestionCreateReqDto {
	
	@Schema(description = "문의사항 카테고리 ID", example = "2")
	private Long questionCategoryId;
	
	@Schema(description = "회선 ID", example = "3")
	private Long lineId;
	
	@Schema(description = "문의사항 제목", example = "요금제 관련 문의드립니다")
	private String title;
	
	@Schema(description = "문의사항 내용", example = "가격이 더 비싸진 것 같아요")
	private String content;
	
	@Schema(description = "문의사항 첨부 파일 목록")
	private List<QuestionAttachmentDto> attachments;
}
