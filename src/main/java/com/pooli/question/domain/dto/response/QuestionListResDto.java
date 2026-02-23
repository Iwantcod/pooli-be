package com.pooli.question.domain.dto.response;

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
@Schema(description = "사용자 문의사항 목록 응답 DTO")
public class QuestionListResDto {

	@Schema(description = "문의사항 ID", example = "2")
	private Long questionId;
	
	@Schema(description = "문의사항 카테고리 ID", example = "3")
	private Long questionCategoryId;
	
	@Schema(description = "회선 ID", example = "4")
	private Long lineId;
	
	@Schema(description = "문의사항 제목", example = "데이터 한도 관련 문의드립니다")
	private String title;
	
	@Schema(description = "관리자의 답변 여부", example = "false")
	private Boolean isAnswer;
	
}
