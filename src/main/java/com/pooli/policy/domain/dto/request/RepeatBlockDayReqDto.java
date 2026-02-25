package com.pooli.policy.domain.dto.request;

import java.time.LocalTime;

import com.pooli.policy.domain.enums.DayOfWeek;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "특정 구성원에 적용될 반복적 차단 요일 요청 dto")
public class RepeatBlockDayReqDto {
	
	@Schema(description = "특정 구성원에 적용될 반복적 차단 요일")
	private DayOfWeek dayOfWeek;
	
	@Schema(description = "시작 시간", example = "14:01:00")
    private LocalTime startAt;
	
	@Schema(description = "종료 시간", example = "19:20:00")
    private LocalTime endAt;
}
