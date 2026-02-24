package com.pooli.policy.domain.dto.request;

import com.pooli.policy.domain.enums.DayOfWeek;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "반복적 차단 정책 수정 요청")
public class RepeatBlockPolicyReqDto {
	
	@Schema(description = "활성화 상태", example = "false")
	private Boolean isActive;
	
	@Schema(description = "시작 시간", example = "1401")
	private Integer startAt;
	
	@Schema(description = "종료 시간", example = "2003")
	private Integer endAt;	
	
	DayOfWeek dayOfWeek;
}
