package com.pooli.policy.domain.dto.response;

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
@Schema(description = "특정 구성원에 적용된 즉시 차단 정책")
public class ImmediateBlockResDto {
	
	@Schema(description = "회선 ID", example = "1001")
	private Long lineId;

	@Schema(description = "차단 종료 시간", example = "2026-02-20T14:30:00")
	private String blockEndAt;	
	
	@Schema(description = "차단 상태", example = "false")
	private Boolean isBlock;
	
	@Schema(description = "차단 종료 시간", example = "2026-02-20T14:30:00")
	private String updatedAt;
}
