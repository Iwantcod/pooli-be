package com.pooli.policy.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "즉시 차단 정책 수정 요청")
public class ImmediateBlockReqDto {

	@Schema(description = "차단 종료 시간", example = "2026-02-20T14:30:00")
	private String blockEndAt;	
	
	@Schema(description = "차단 상태", example = "false")
	private Boolean isBlocked;
	
}
