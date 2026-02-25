package com.pooli.policy.domain.dto.response;

import java.time.LocalTime;

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
@Schema(description = "특정 구성원에 적용된 반복적 차단 요일")
public class RepeatBlockDayResDto {
	
	@Schema(description = "특정 구성원에 적용된 반복적 차단 요일")
	private DayOfWeek dayOfWeek;
	
	@Schema(description = "시작 시간", example = "14:01:00")
    private LocalTime startAt;
	
	@Schema(description = "종료 시간", example = "19:20:00")
    private LocalTime endAt;
}
