package com.pooli.question.domain.dto.response;

import com.pooli.notification.domain.dto.response.UnreadCountsResDto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "사용자 문의사항 응답 DTO")
public class QuestionListResDto {

	@Schema(description = "미읽음 개수", example = "15")
	private Long unreadCount;
}
