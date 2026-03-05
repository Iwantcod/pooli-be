package com.pooli.policy.domain.dto.response;

import java.util.List;

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
	
    private List<RepeatBlockDayResDto> days;
}
