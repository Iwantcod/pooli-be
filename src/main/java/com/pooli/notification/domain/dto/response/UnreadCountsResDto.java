package com.pooli.notification.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "알림 미읽음 개수 응답 DTO")
public class UnreadCountsResDto {
	
	@Schema(description = "회선 ID", example = "1")
	private Long lineId;

	@Schema(description = "미읽음 개수", example = "15")
	private Long unreadCount;
}
