package com.pooli.policy.domain.dto.response;


import com.pooli.policy.domain.enums.DayOfWeek;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "특정 구성원에 적용된 반복적 차단 정책")
public class RepeatBlockPolicyResDto {
	
	@Schema(description = "반복적 차단 ID", example = "1001")
	private Long repeatBlockId;
	
	@Schema(description = "회선 ID", example = "2002")
	private Long lineId;
	
	@Schema(description = "활성화 상태", example = "false")
	private Boolean isActive;
	
	@Schema(description = "시작 시간", example = "1401")
	private Integer startAt;
	
	@Schema(description = "종료 시간", example = "2003")
	private Integer endAt;	
	
	@Schema(description = "생성 시점", example = "2026-02-10T14:30:00")
	private String createAt;	
	
	@Schema(description = "수정 시점", example = "2026-02-20T14:30:00")
	private String updateAt;	
	
	@Schema(description = "삭제 시점", example = "2026-02-30T14:30:00")
	private String deleteAt;	
	
	DayOfWeek dayOfWeek;
}
