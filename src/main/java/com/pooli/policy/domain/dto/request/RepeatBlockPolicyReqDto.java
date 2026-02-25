package com.pooli.policy.domain.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "반복적 차단 정책 수정 요청")
public class RepeatBlockPolicyReqDto {
	
	@Schema(description = "활성화 상태", example = "false")
	private Boolean isActive;
	
	@Schema(description = "차단 요일 목록")
	private List<RepeatBlockDayReqDto> days;
}
