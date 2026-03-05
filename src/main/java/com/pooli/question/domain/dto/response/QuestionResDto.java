package com.pooli.question.domain.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
@Schema(description = "사용자 문의사항 응답 DTO")
public class QuestionResDto {

	@Schema(description = "문의사항 ID", example = "1")
	private Long questionId;
	
	@Schema(description = "문의사항 카테고리 ID", example = "2")
	private Integer questionCategoryId;
	
	@Schema(description = "회선 ID", example = "3")
	private Long lineId;
	
	@Schema(description = "문의사항 제목", example = "요금제 관련 문의드립니다")
	private String title;
	
	@Schema(description = "문의사항 내용", example = "가격이 더 비싸진 것 같아요")
	private String content;
	
	@Schema(description = "관리자의 답변 여부", example = "false")
	private Boolean isAnswer;
	
	@Schema(description = "문의사항 생성 시점", example = "2026-02-23T14:30:00")
	private LocalDateTime createdAt;

	@Schema(description = "문의사항 첨부 파일 URL 리스트")
	private List<AttachmentResDto> attachments;

	@Schema(description = "관리자 답변")
	private AnswerResDto answer;
}
